package com.smartgn.management.devices.application;

import com.smartgn.management.integration.CredentialVault;
import com.smartgn.management.integration.DependencyFailure;
import com.smartgn.management.integration.TelemetryGateway;
import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.DomainException;
import com.smartgn.management.shared.domain.ManagementModels.*;
import com.smartgn.management.shared.domain.Role;
import com.smartgn.management.shared.domain.ListWindow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class DeviceOperations {
    /** The secret is returned only by creation/rotation, never by a query. */
    public record ProvisioningReceipt(Device device, UUID credentialId, String mqttUsername, String oneTimeCredential) {
        @Override public String toString() { return "ProvisioningReceipt[device=" + device.id() + ", credential=REDACTED]"; }
    }
    public record DeviceView(Device device, String connectionState, Instant lastContact) {}
    public record ProvisioningJob(String action, long associationVersion, UUID credentialId, UUID actorId, UUID correlationId) {}
    private final ManagementStore store;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final CredentialVault vault;
    private final TelemetryGateway telemetry;
    private final ObjectMapper mapper;

    public DeviceOperations(ManagementStore store, TransactionRunner transactions, Clock clock,
                            CredentialVault vault, TelemetryGateway telemetry, ObjectMapper mapper) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
        this.vault = vault;
        this.telemetry = telemetry;
        this.mapper = mapper;
    }

    public ProvisioningReceipt register(Actor actor, UUID installationId, String serialNumber,
                                       String location, Instant installedAt) {
        return register(actor, installationId, serialNumber, location, installedAt, null);
    }

    public ProvisioningReceipt register(Actor actor, UUID installationId, String serialNumber,
                                       String location, Instant installedAt, String replacementGapReason) {
        return register(actor,installationId,serialNumber,location,installedAt,replacementGapReason,UUID.randomUUID());
    }
    public ProvisioningReceipt register(Actor actor, UUID installationId, String serialNumber,
                                       String location, Instant installedAt, String replacementGapReason, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        requireText(serialNumber, "Device serial number", 160);
        requireText(location, "Device location", 300);
        if (installedAt == null || installedAt.isAfter(clock.instant())) {
            throw new DomainException("INVALID_INPUT", "Installation date must not be in the future");
        }
        String plaintext = vault.generate();
        return transactions.run(() -> {
            Installation installation = store.lockInstallation(installationId).orElseThrow(() -> missing("Installation"));
            requireRegistration(installation);
            validateTarget(installation);
            if (installation.deviceId() != null) {
                Device previous = store.lockDevice(installation.deviceId()).orElseThrow(() -> missing("Previous device"));
                requireRevoked(previous);
                requireText(replacementGapReason, "Replacement buffer-gap acknowledgement", 1000);
                store.endAssociation(previous.id(), previous.associationVersion(), clock.instant());
                enqueue(actor, previous, null, "SYNC_END", correlationId);
                audit(actor, "REPLACE_REVOKED_DEVICE", previous.id(),
                        "Explicit unresolved telemetry gap accepted before hardware replacement: " + replacementGapReason.trim(), correlationId);
            }
            Device device = new Device(UUID.randomUUID(), serialNumber.trim(), installation.accountId(),
                    installation.propertyId(), installation.pointId(), installationId, installation.installerId(),
                    location.trim(), installedAt, DeviceStatus.PENDING, 1, false, false, 0);
            store.saveDevice(device, -1);
            store.appendAssociation(new Association(device.id(), 1, device.accountId(), device.propertyId(), device.pointId(),
                    clock.instant(), null, false));
            store.saveInstallation(withDevice(installation, device.id()), installation.version());
            Credential credential = credential(device, plaintext, 1);
            store.saveCredential(credential);
            enqueue(actor, device, credential.id(), "PROVISION", correlationId);
            audit(actor, "REGISTER", device.id(), "Device registered; provisioning is pending", correlationId);
            return new ProvisioningReceipt(device, credential.id(), device.id().toString(), plaintext);
        });
    }

    public List<DeviceView> query(Actor actor, UUID pointId) {
        return query(actor,pointId,ListWindow.defaults(),null);
    }
    public List<DeviceView> query(Actor actor, UUID pointId, ListWindow window, DeviceStatus status) {
        List<Device> devices;
        if (pointId == null) {
            actor.requireRole(Role.SUPERADMIN);
            devices = store.listDevices(window,status);
        } else {
            SupplyPoint point = store.findSupplyPoint(pointId).orElseThrow(() -> missing("Supply point"));
            Property property = store.findProperty(point.propertyId()).orElseThrow(() -> missing("Property"));
            if (actor.role() != Role.SUPERADMIN) actor.requireOwner(property.accountId());
            devices = store.findDeviceByPoint(pointId).filter(d -> status==null || d.status()==status).map(List::of).orElseGet(List::of);
            if(window.offset()>0) devices=List.of();
        }
        List<DeviceView> result = new ArrayList<>();
        for (UUID accountId : devices.stream().map(Device::accountId).distinct().toList()) {
            List<Device> group = devices.stream().filter(d -> d.accountId().equals(accountId)).toList();
            List<TelemetryGateway.OperationalStatus> metrics;
            try {
                metrics = telemetry.operationalStatus(accountId, group.stream().map(Device::id).toList());
            } catch (DependencyFailure unavailable) { metrics = List.of(); }
            for (Device device : group) {
                TelemetryGateway.OperationalStatus metric = metrics.stream().filter(m -> m.deviceId().equals(device.id())).findFirst().orElse(null);
                result.add(new DeviceView(device, metric == null ? "UNAVAILABLE" : metric.connectionState(),
                        metric == null ? null : metric.lastContact()));
            }
        }
        return List.copyOf(result);
    }

    public List<Association> history(Actor actor, UUID deviceId) {
        actor.requireRole(Role.SUPERADMIN);
        store.findDevice(deviceId).orElseThrow(() -> missing("Device"));
        return store.listAssociations(deviceId);
    }

    public ProvisioningReceipt rotate(Actor actor, UUID deviceId) {
        return rotate(actor,deviceId,UUID.randomUUID());
    }
    public ProvisioningReceipt rotate(Actor actor, UUID deviceId, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        String plaintext = vault.generate();
        return transactions.run(() -> {
            Device before = store.lockDevice(deviceId).orElseThrow(() -> missing("Device"));
            if (before.status() == DeviceStatus.PENDING) throw new DomainException("CONFLICT", "A device operation is already pending");
            if (before.status() == DeviceStatus.REVOKED) throw new DomainException("INVALID_STATE", "Revoked device requires administrative reassociation");
            long nextCredential = store.listCredentials(deviceId).stream().mapToLong(Credential::credentialVersion).max().orElse(0) + 1;
            retireCredentials(deviceId);
            Device after = copy(before, DeviceStatus.PENDING, false, false);
            store.saveDevice(after, before.version());
            Credential credential = credential(after, plaintext, nextCredential);
            store.saveCredential(credential);
            enqueue(actor, after, credential.id(), "PROVISION", correlationId);
            audit(actor, "ROTATE", deviceId, "Credential rotation pending external confirmation", correlationId);
            return new ProvisioningReceipt(after, credential.id(), deviceId.toString(), plaintext);
        });
    }

    public Device revoke(Actor actor, UUID deviceId) {
        return revoke(actor,deviceId,UUID.randomUUID());
    }
    public Device revoke(Actor actor, UUID deviceId, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        return transactions.run(() -> {
            Device before = store.lockDevice(deviceId).orElseThrow(() -> missing("Device"));
            if (before.status() == DeviceStatus.REVOKED) return before;
            retireCredentials(deviceId);
            Device after = copy(before, DeviceStatus.REVOKED, false, before.telemetryConfirmed());
            store.saveDevice(after, before.version());
            enqueue(actor, after, null, "REVOKE", correlationId);
            audit(actor, "REVOKE", deviceId, "Revocation requested; broker confirmation is pending", correlationId);
            return after;
        });
    }

    public ProvisioningReceipt reassociate(Actor actor, UUID deviceId, UUID installationId,
                                           boolean resolveGap, String resolutionReason) {
        return reassociate(actor,deviceId,installationId,resolveGap,resolutionReason,UUID.randomUUID());
    }
    public ProvisioningReceipt reassociate(Actor actor, UUID deviceId, UUID installationId,
                                           boolean resolveGap, String resolutionReason, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        Device snapshot = store.findDevice(deviceId).orElseThrow(() -> missing("Device"));
        requireRevoked(snapshot);
        TelemetryGateway.DrainResult drain;
        try { drain = telemetry.drain(deviceId, snapshot.associationVersion()); }
        catch (DependencyFailure e) {
            if (!resolveGap) throw new DomainException("DEPENDENCY_UNAVAILABLE", e.getMessage());
            drain = new TelemetryGateway.DrainResult(false, 0, "Unavailable dependency; pending count unknown");
        }
        if (!drain.drained() && !resolveGap) throw new DomainException("CONFLICT", "Previous association has an undrained or unconfirmed telemetry buffer");
        if (!drain.drained()) requireText(resolutionReason, "Administrative gap resolution", 1000);
        String evidence = drain.drained() ? "Drain confirmed: " + drain.evidenceId()
                : "Explicit unresolved telemetry gap: " + resolutionReason.trim();
        String plaintext = vault.generate();
        return transactions.run(() -> {
            Installation installation = store.lockInstallation(installationId).orElseThrow(() -> missing("Installation"));
            Device before = store.lockDevice(deviceId).orElseThrow(() -> missing("Device"));
            if (before.version() != snapshot.version()) throw new DomainException("CONFLICT", "Device changed during buffer verification");
            requireRevoked(before);
            if (installation.id().equals(before.installationId())) {
                if (installation.status() != InstallationStatus.IN_PROGRESS || !deviceId.equals(installation.deviceId())) {
                    throw new DomainException("INVALID_STATE", "Recovery requires the original in-progress installation");
                }
            } else {
                Installation original = store.findInstallation(before.installationId()).orElseThrow(() -> missing("Original installation"));
                if (original.status() != InstallationStatus.COMPLETED && original.status() != InstallationStatus.CANCELLED) {
                    throw new DomainException("INVALID_STATE", "Complete or cancel the original installation before moving its device");
                }
                requireRegistration(installation);
                if (installation.deviceId() != null) throw new DomainException("CONFLICT", "Target installation already has a device");
            }
            validateTarget(installation);
            if (before.pointId().equals(installation.pointId()) && before.status() != DeviceStatus.REVOKED) {
                throw new DomainException("CONFLICT", "Device is already associated with this point");
            }
            Instant now = clock.instant();
            Association previous=store.findAssociation(deviceId,before.associationVersion()).orElseThrow(()->missing("Association"));
            if(previous.validTo()==null) store.endAssociation(deviceId, before.associationVersion(), now);
            retireCredentials(deviceId);
            Device after = new Device(deviceId, before.serialNumber(), installation.accountId(), installation.propertyId(),
                    installation.pointId(), installationId, installation.installerId(), before.location(), now,
                    DeviceStatus.PENDING, before.associationVersion() + 1, false, false, before.version() + 1);
            store.saveDevice(after, before.version());
            store.appendAssociation(new Association(deviceId, after.associationVersion(), after.accountId(), after.propertyId(),
                    after.pointId(), now, null, false));
            store.saveInstallation(withDevice(installation, deviceId), installation.version());
            long next = store.listCredentials(deviceId).stream().mapToLong(Credential::credentialVersion).max().orElse(0) + 1;
            Credential credential = credential(after, plaintext, next);
            store.saveCredential(credential);
            enqueue(actor, after, credential.id(), "PROVISION", correlationId);
            audit(actor, "REASSOCIATE", deviceId, evidence, correlationId);
            return new ProvisioningReceipt(after, credential.id(), deviceId.toString(), plaintext);
        });
    }

    private void requireRegistration(Installation installation) {
        if (installation.status() != InstallationStatus.IN_PROGRESS) {
            throw new DomainException("INVALID_STATE", "Registration requires an in-progress installation without a device");
        }
        if (installation.deviceId() != null) {
            Device prior = store.findDevice(installation.deviceId()).orElseThrow(() -> missing("Previous device"));
            requireRevoked(prior);
        }
    }

    private static void requireRevoked(Device device) {
        if (device.status() != DeviceStatus.REVOKED || !device.brokerConfirmed()) {
            throw new DomainException("INVALID_STATE", "Reassociation or replacement requires confirmed broker revocation");
        }
    }

    private void validateTarget(Installation installation) {
        Property property = store.lockProperty(installation.propertyId()).orElseThrow(() -> missing("Property"));
        SupplyPoint point = store.lockSupplyPoint(installation.pointId()).orElseThrow(() -> missing("Supply point"));
        Installer installer = store.findInstaller(installation.installerId()).orElseThrow(() -> missing("Installer"));
        if (!property.active() || !point.active() || !installer.enabled()
                || !point.propertyId().equals(property.id()) || !property.accountId().equals(installation.accountId())) {
            throw new DomainException("INVALID_STATE", "Installation account, active resources and enabled installer must agree");
        }
    }

    private Credential credential(Device device, String plaintext, long version) {
        return new Credential(UUID.randomUUID(), device.id(), vault.protect(device.id(), plaintext),
                "PENDING", version, clock.instant(), null);
    }
    private void retireCredentials(UUID deviceId) {
        for (Credential before : store.listCredentials(deviceId)) {
            if ("ACTIVE".equals(before.state()) || "PENDING".equals(before.state())) {
                store.saveCredential(new Credential(before.id(), deviceId, before.protectedValue(), "REVOKED",
                        before.credentialVersion(), before.createdAt(), clock.instant()));
            }
        }
    }
    /** Called within the installation cancellation transaction, after locking its installation. */
    public void endForCancellation(Actor actor, Installation installation, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        Device device=store.lockDevice(installation.deviceId()).orElseThrow(()->missing("Device"));
        if(!device.installationId().equals(installation.id())) throw new DomainException("CONFLICT","Device has moved to another installation");
        requireRevoked(device);
        Association association=store.findAssociation(device.id(),device.associationVersion()).orElseThrow(()->missing("Association"));
        if(association.validTo()==null) {
            store.endAssociation(device.id(),device.associationVersion(),clock.instant());
            enqueue(actor,device,null,"SYNC_END",correlationId);
            audit(actor,"ASSOCIATION_ENDED_FOR_CANCELLATION",device.id(),"Association history preserved; end synchronization pending",correlationId);
        }
    }
    private void enqueue(Actor actor, Device device, UUID credentialId, String action, UUID correlationId) {
        ProvisioningJob payload = new ProvisioningJob(action, device.associationVersion(), credentialId,
                actor.accountId(), correlationId);
        Instant now = clock.instant();
        store.enqueue(new OutboxJob(UUID.randomUUID(), "REVOKE".equals(action) ? "DEVICE_REVOKE" : "DEVICE", device.id(), mapper.writeValueAsString(payload),
                "PENDING", now, now, 0, null, null, null));
    }
    private void audit(Actor actor, String action, UUID deviceId, String details, UUID correlationId) {
        store.audit(new Audit(UUID.randomUUID(), actor.accountId(), action, "DEVICE", deviceId,
                correlationId, details, clock.instant()));
    }
    public static Device copy(Device before, DeviceStatus status, boolean broker, boolean telemetry) {
        return new Device(before.id(), before.serialNumber(), before.accountId(), before.propertyId(), before.pointId(),
                before.installationId(), before.installerId(), before.location(), before.installedAt(), status,
                before.associationVersion(), broker, telemetry, before.version() + 1);
    }
    private static Installation withDevice(Installation before, UUID deviceId) {
        return new Installation(before.id(), before.accountId(), before.propertyId(), before.pointId(), before.installerId(),
                before.status(), deviceId, before.createdAt(), before.completedAt(), before.version() + 1);
    }
    private static void requireText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new DomainException("INVALID_INPUT", field + " is invalid");
    }
    private static DomainException missing(String resource) { return new DomainException("NOT_FOUND", resource + " was not found"); }
}
