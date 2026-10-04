package com.smartgn.management.devices.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.smartgn.management.devices.application.DeviceOperations.ProvisioningJob;
import com.smartgn.management.integration.*;
import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.ManagementModels.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeviceProvisioningWorkerTest {
    private final ManagementStore store = mock(ManagementStore.class);
    private final BrokerGateway broker = mock(BrokerGateway.class);
    private final TelemetryGateway telemetry = mock(TelemetryGateway.class);
    private final CredentialVault vault = new CredentialVault(Base64.getEncoder().encodeToString(new byte[32]));
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final Instant now = Instant.parse("2026-10-04T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final TransactionRunner transactions = new TransactionRunner() { public <T> T run(Supplier<T> work) { return work.get(); } };
    private final UUID deviceId = UUID.randomUUID(), credentialId = UUID.randomUUID();
    private final AtomicReference<Device> current = new AtomicReference<>();
    private final AtomicReference<Credential> credential = new AtomicReference<>();
    private OutboxJob job;
    private DeviceProvisioningWorker worker;

    @BeforeEach void setup() {
        Device initial = new Device(deviceId, "sensor-1", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Kitchen", now, DeviceStatus.PENDING, 1, false, false, 0);
        current.set(initial);
        credential.set(new Credential(credentialId, deviceId, vault.protect(deviceId, "secret-mqtt-password"), "PENDING", 1, now, null));
        ProvisioningJob payload = new ProvisioningJob("PROVISION", 1, credentialId, UUID.randomUUID(), UUID.randomUUID());
        job = new OutboxJob(UUID.randomUUID(), "DEVICE", deviceId, mapper.writeValueAsString(payload), "PROCESSING", now, now,
                0, UUID.randomUUID(), now.plusSeconds(60), null);
        when(store.claimJobs(anyInt(), any())).thenReturn(List.of(job));
        when(store.ownsJob(job.id(), job.claimToken())).thenReturn(true);
        when(store.lockDevice(deviceId)).thenAnswer(invocation -> Optional.of(current.get()));
        when(store.findCredential(credentialId)).thenAnswer(invocation -> Optional.of(credential.get()));
        when(store.listAssociations(deviceId)).thenReturn(List.of(new Association(deviceId, 1, initial.accountId(), initial.propertyId(), initial.pointId(), now, null, false)));
        doAnswer(invocation -> { current.set(invocation.getArgument(0)); return null; }).when(store).saveDevice(any(), anyLong());
        doAnswer(invocation -> { credential.set(invocation.getArgument(0)); return null; }).when(store).saveCredential(any());
        when(store.completeJob(job.id(), job.claimToken())).thenReturn(true);
        worker = new DeviceProvisioningWorker(store, transactions, clock, vault, telemetry, broker, mapper);
    }

    @Test void publicationIsEnabledOnlyAfterExactAssociationSynchronization() {
        doAnswer(invocation -> {
            assertThat(current.get().status()).isNotEqualTo(DeviceStatus.ACTIVE);
            assertThat(current.get().brokerConfirmed()).isFalse();
            return null;
        }).when(telemetry).synchronize(any());
        worker.runOnce();
        var order = inOrder(broker, telemetry);
        order.verify(broker).stage(deviceId, "secret-mqtt-password");
        order.verify(telemetry).synchronize(argThat(value -> value.version() == 1 && value.deviceId().equals(deviceId)));
        order.verify(broker).activate(deviceId);
        assertThat(current.get().status()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(current.get().brokerConfirmed()).isTrue();
        assertThat(current.get().telemetryConfirmed()).isTrue();
        assertThat(credential.get().state()).isEqualTo("ACTIVE");
        assertThat(job.payload()).doesNotContain("secret-mqtt-password");
    }

    @Test void failedSynchronizationKeepsWorkDurableAndRetryCanActivateIt() {
        doThrow(new DependencyFailure("Telemetry")).doNothing().when(telemetry).synchronize(any());
        worker.runOnce();
        verify(broker, never()).activate(any());
        verify(store).retryJob(eq(job.id()), eq(job.claimToken()), any(), eq("INTEGRATION_NOT_CONFIRMED:SYNC_ASSOCIATION"));
        assertThat(current.get().status()).isEqualTo(DeviceStatus.FAILED);
        assertThat(credential.get().state()).isEqualTo("PENDING");
        worker.runOnce();
        assertThat(current.get().status()).isEqualTo(DeviceStatus.ACTIVE);
    }

    @Test void obsoleteCredentialJobCannotReactivateARevokedDevice() {
        Device before = current.get();
        current.set(DeviceOperations.copy(before, DeviceStatus.REVOKED, false, false));
        credential.set(new Credential(credentialId, deviceId, credential.get().protectedValue(), "REVOKED", 1, now, now));
        worker.runOnce();
        verifyNoInteractions(broker, telemetry);
        verify(store).completeJob(job.id(), job.claimToken());
        assertThat(current.get().status()).isEqualTo(DeviceStatus.REVOKED);
    }

    @Test void expiredLeaseCannotChangeDeviceStateOrCallDependencies() {
        when(store.ownsJob(job.id(), job.claimToken())).thenReturn(false);
        worker.runOnce();
        verifyNoInteractions(broker, telemetry);
        verify(store, never()).saveDevice(any(), anyLong());
    }
    @Test void asynchronousWorkRetainsTheRequestCorrelationAndRestoresThePreviousContext() {
        String expected=mapper.readValue(job.payload(),ProvisioningJob.class).correlationId().toString();
        org.slf4j.MDC.put("correlationId","previous-context");
        try {
            doAnswer(call -> {assertThat(org.slf4j.MDC.get("correlationId")).isEqualTo(expected);return null;}).when(broker).stage(any(),any());
            doAnswer(call -> {assertThat(org.slf4j.MDC.get("correlationId")).isEqualTo(expected);return null;}).when(telemetry).synchronize(any());
            worker.runOnce();
            verify(store).audit(argThat(a->a.correlationId().toString().equals(expected)));
            assertThat(org.slf4j.MDC.get("correlationId")).isEqualTo("previous-context");
        } finally { org.slf4j.MDC.remove("correlationId"); }
    }
}
