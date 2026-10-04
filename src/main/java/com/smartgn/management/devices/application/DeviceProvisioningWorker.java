package com.smartgn.management.devices.application;

import com.smartgn.management.devices.application.DeviceOperations.ProvisioningJob;
import com.smartgn.management.integration.BrokerGateway;
import com.smartgn.management.integration.CredentialVault;
import com.smartgn.management.integration.TelemetryGateway;
import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.DomainException;
import com.smartgn.management.shared.domain.ManagementModels.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;

/** Durable, leased per-device work. All network calls occur outside database transactions. */
public final class DeviceProvisioningWorker {
    private final ManagementStore store;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final CredentialVault vault;
    private final TelemetryGateway telemetry;
    private final BrokerGateway broker;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean();

    public DeviceProvisioningWorker(ManagementStore store, TransactionRunner transactions, Clock clock,
                                    CredentialVault vault, TelemetryGateway telemetry, BrokerGateway broker, ObjectMapper mapper) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
        this.vault = vault;
        this.telemetry = telemetry;
        this.broker = broker;
        this.mapper = mapper;
    }

    public void runOnce() {
        if (!running.compareAndSet(false, true)) return;
        try {
            List<OutboxJob> jobs = transactions.run(() -> store.claimJobs(1, Duration.ofSeconds(60)));
            for (OutboxJob job : jobs) process(job);
        } finally { running.set(false); }
    }

    private void process(OutboxJob job) {
        ProvisioningJob payload;
        String step = "LOAD_JOB";
        String previousCorrelation=org.slf4j.MDC.get("correlationId");
        try {
            payload = mapper.readValue(job.payload(), ProvisioningJob.class);
            if(payload.correlationId()!=null) org.slf4j.MDC.put("correlationId",payload.correlationId().toString());
            Device device = current(job, payload);
            if (device == null) { completeStale(job); return; }
            if ("REVOKE".equals(payload.action())) {
                step = "REVOKE_BROKER";
                broker.revoke(device.id());
                finish(job, payload, true);
                return;
            }
            if ("SYNC_END".equals(payload.action())) {
                step = "SYNC_ASSOCIATION_END";
                Association association = store.findAssociation(device.id(), payload.associationVersion()).orElseThrow();
                telemetry.synchronize(new TelemetryGateway.Association(device.id(), association.version(), association.accountId(),
                        association.propertyId(), association.pointId(), association.validFrom(), association.validTo()));
                finish(job, payload, true);
                return;
            }
            Credential credential = store.findCredential(payload.credentialId()).orElseThrow();
            step = "STAGE_BROKER";
            broker.stage(device.id(), vault.reveal(device.id(), credential.protectedValue()));
            if (current(job, payload) == null) { revokeIfOwned(job); completeStale(job); return; }
            // Historical versions are preserved and their end of validity is synchronized first.
            step = "SYNC_ASSOCIATION";
            for (Association association : store.listAssociations(device.id())) {
                if (association.version() == payload.associationVersion()
                        || association.version() == payload.associationVersion() - 1) {
                    telemetry.synchronize(new TelemetryGateway.Association(association.deviceId(), association.version(),
                            association.accountId(), association.propertyId(), association.pointId(),
                            association.validFrom(), association.validTo()));
                }
            }
            step = "CONFIRM_PROJECTION";
            if (!confirmProjection(job, payload)) { revokeIfOwned(job); completeStale(job); return; }
            if (current(job, payload) == null) { revokeIfOwned(job); completeStale(job); return; }
            // Enabling the ACL is the final external step, after the exact association acknowledgement.
            step = "ACTIVATE_BROKER";
            broker.activate(device.id());
            step = "PERSIST_ACTIVATION";
            if (!finish(job, payload, false)) {
                revokeIfOwned(job);
                completeStale(job);
            }
        } catch (Exception failure) {
            // Never persist exception payloads: dependencies may include credentials in their messages.
            org.slf4j.LoggerFactory.getLogger(DeviceProvisioningWorker.class).warn(
                    "Device integration retry: step={} jobId={} correlationId={} errorType={}", step, job.id(), org.slf4j.MDC.get("correlationId"), failure.getClass().getSimpleName());
            retry(job,step);
        } finally {
            if(previousCorrelation==null) org.slf4j.MDC.remove("correlationId");
            else org.slf4j.MDC.put("correlationId",previousCorrelation);
        }
    }

    private Device current(OutboxJob job, ProvisioningJob payload) {
        return transactions.run(() -> {
            Device device = store.lockDevice(job.aggregateId()).orElse(null);
            if (!store.ownsJob(job.id(), job.claimToken())) return null;
            return matches(device, payload) ? device : null;
        });
    }

    private boolean matches(Device device, ProvisioningJob payload) {
        if (device == null || device.associationVersion() != payload.associationVersion()) return false;
        if ("REVOKE".equals(payload.action())) return device.status() == DeviceStatus.REVOKED;
        if ("SYNC_END".equals(payload.action())) return device.status() == DeviceStatus.REVOKED && device.brokerConfirmed();
        if (!"PROVISION".equals(payload.action()) || payload.credentialId() == null
                || device.status() == DeviceStatus.REVOKED) return false;
        Credential credential = store.findCredential(payload.credentialId()).orElse(null);
        return credential != null && credential.deviceId().equals(device.id())
                && ("PENDING".equals(credential.state()) || "ACTIVE".equals(credential.state()));
    }

    private boolean confirmProjection(OutboxJob job, ProvisioningJob payload) {
        return transactions.run(() -> {
            Device before = store.lockDevice(job.aggregateId()).orElse(null);
            if (!store.ownsJob(job.id(), job.claimToken())) return false;
            if (!matches(before, payload)) return false;
            store.confirmAssociation(before.id(), payload.associationVersion());
            store.saveDevice(DeviceOperations.copy(before, DeviceStatus.PENDING, false, true), before.version());
            return true;
        });
    }

    private boolean finish(OutboxJob job, ProvisioningJob payload, boolean revoked) {
        return transactions.run(() -> {
            Device before = store.lockDevice(job.aggregateId()).orElse(null);
            if (!store.ownsJob(job.id(), job.claimToken())) return false;
            if (!matches(before, payload)) return false;
            if (!store.completeJob(job.id(), job.claimToken())) {
                throw new DomainException("CONFLICT", "Device worker lease expired before confirmation");
            }
            Device after = DeviceOperations.copy(before, revoked ? DeviceStatus.REVOKED : DeviceStatus.ACTIVE,
                    true, before.telemetryConfirmed());
            store.saveDevice(after, before.version());
            if (!revoked) {
                Credential credential = store.findCredential(payload.credentialId()).orElseThrow();
                store.saveCredential(new Credential(credential.id(), credential.deviceId(), credential.protectedValue(), "ACTIVE",
                        credential.credentialVersion(), credential.createdAt(), null));
            }
            store.audit(new Audit(java.util.UUID.randomUUID(), payload.actorId(), "SYNC_END".equals(payload.action()) ? "ASSOCIATION_END_CONFIRMED"
                    : revoked ? "REVOCATION_CONFIRMED" : "ACTIVATION_CONFIRMED",
                    "DEVICE", before.id(), payload.correlationId(), "Broker operation and association confirmed", clock.instant()));
            return true;
        });
    }

    private void completeStale(OutboxJob job) {
        transactions.run(() -> { store.completeJob(job.id(), job.claimToken()); return null; });
    }

    private void revokeIfOwned(OutboxJob job) {
        if (transactions.run(() -> store.ownsJob(job.id(), job.claimToken()))) broker.revoke(job.aggregateId());
    }

    private void retry(OutboxJob job, String step) {
        ProvisioningJob parsed;
        try { parsed = mapper.readValue(job.payload(), ProvisioningJob.class); }
        catch (Exception malformed) { parsed = null; }
        ProvisioningJob payload = parsed;
        transactions.run(() -> {
            Device device = store.lockDevice(job.aggregateId()).orElse(null);
            if (!store.ownsJob(job.id(), job.claimToken())) return null;
            if (payload != null && !matches(device, payload)) {
                store.completeJob(job.id(), job.claimToken());
                return null;
            }
            if (device != null && device.status() == DeviceStatus.PENDING) {
                store.saveDevice(DeviceOperations.copy(device, DeviceStatus.FAILED, false, device.telemetryConfirmed()), device.version());
            }
            long seconds = device != null && device.status() == DeviceStatus.REVOKED ? 1 : Math.min(30, 1L << Math.min(5, job.attempts()));
            store.retryJob(job.id(), job.claimToken(), clock.instant().plusSeconds(seconds), "INTEGRATION_NOT_CONFIRMED:"+step);
            return null;
        });
    }
}
