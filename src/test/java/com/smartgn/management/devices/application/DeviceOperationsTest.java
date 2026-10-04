package com.smartgn.management.devices.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.smartgn.management.integration.CredentialVault;
import com.smartgn.management.integration.TelemetryGateway;
import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.*;
import com.smartgn.management.shared.domain.ManagementModels.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeviceOperationsTest {
    private final ManagementStore store = mock(ManagementStore.class);
    private final TelemetryGateway telemetry = mock(TelemetryGateway.class);
    private final Instant now = Instant.parse("2026-10-04T00:00:00Z");
    private final UUID accountId = UUID.randomUUID(), propertyId = UUID.randomUUID(), pointId = UUID.randomUUID(), installerId = UUID.randomUUID(), installationId = UUID.randomUUID(), deviceId = UUID.randomUUID();
    private final Actor admin = new Actor(UUID.randomUUID(), Role.SUPERADMIN, Plan.FREE);
    private DeviceOperations operations;
    private Device revoked;
    private Installation ongoing;

    @BeforeEach void setup() {
        TransactionRunner tx = new TransactionRunner() { public <T> T run(Supplier<T> work) { return work.get(); } };
        var vault = new CredentialVault(Base64.getEncoder().encodeToString(new byte[32]));
        operations = new DeviceOperations(store, tx, Clock.fixed(now, ZoneOffset.UTC), vault, telemetry, JsonMapper.builder().findAndAddModules().build());
        revoked = new Device(deviceId, "meter", accountId, propertyId, pointId, installationId, installerId, "Kitchen", now,
                DeviceStatus.REVOKED, 1, true, true, 4);
        ongoing = new Installation(installationId, accountId, propertyId, pointId, installerId, InstallationStatus.IN_PROGRESS, deviceId, now, null, 4);
        when(store.findDevice(deviceId)).thenReturn(Optional.of(revoked));
        when(store.lockDevice(deviceId)).thenReturn(Optional.of(revoked));
        when(store.findInstallation(installationId)).thenReturn(Optional.of(ongoing));
        when(store.lockInstallation(installationId)).thenReturn(Optional.of(ongoing));
        when(store.lockProperty(propertyId)).thenReturn(Optional.of(new Property(propertyId, accountId, "Home", "Street", "HOME", true, 0)));
        when(store.lockSupplyPoint(pointId)).thenReturn(Optional.of(new SupplyPoint(pointId, propertyId, "supply", "Kitchen", true, 0)));
        when(store.findInstaller(installerId)).thenReturn(Optional.of(new Installer(installerId, "Ana", "Tech", "doc", "phone", "mail@example.test", true, 0)));
        when(store.listCredentials(deviceId)).thenReturn(List.of());
        when(store.findAssociation(deviceId,1)).thenReturn(Optional.of(new Association(deviceId,1,accountId,propertyId,pointId,now,null,true)));
    }

    @Test void reassociationRequiresConfirmedRevocationBeforeCheckingTheBuffer() {
        Device active = DeviceOperations.copy(revoked, DeviceStatus.ACTIVE, true, true);
        when(store.findDevice(deviceId)).thenReturn(Optional.of(active));
        assertThatThrownBy(() -> operations.reassociate(admin, deviceId, installationId, false, null)).isInstanceOf(DomainException.class);
        verifyNoInteractions(telemetry);
    }

    @Test void undrainedBufferNeedsAnExplicitAdministrativeResolution() {
        when(telemetry.drain(deviceId, 1)).thenReturn(new TelemetryGateway.DrainResult(false, 3, "pending"));
        assertThatThrownBy(() -> operations.reassociate(admin, deviceId, installationId, false, null)).isInstanceOf(DomainException.class);
        verify(store, never()).saveDevice(any(), anyLong());
        var result = operations.reassociate(admin, deviceId, installationId, true, "Hardware buffer is irrecoverable; gap acknowledged");
        assertThat(result.device().associationVersion()).isEqualTo(2);
        assertThat(result.device().status()).isEqualTo(DeviceStatus.PENDING);
        verify(store).endAssociation(deviceId, 1, now);
        verify(store).audit(argThat(audit -> audit.action().equals("REASSOCIATE") && audit.details().contains("Explicit unresolved telemetry gap")));
    }

    @Test void sameInProgressInstallationCanRecoverItsRevokedDeviceWithoutLosingHistory() {
        when(telemetry.drain(deviceId, 1)).thenReturn(new TelemetryGateway.DrainResult(true, 0, "confirmed-drain"));
        var result = operations.reassociate(admin, deviceId, installationId, false, null);
        assertThat(result.device().installationId()).isEqualTo(installationId);
        assertThat(result.device().associationVersion()).isEqualTo(2);
        verify(store).appendAssociation(argThat(value -> value.version() == 2 && value.accountId().equals(accountId)));
        verify(store).saveInstallation(argThat(value -> value.deviceId().equals(deviceId)), eq(ongoing.version()));
    }

    @Test void replacementDeviceCanRecoverAnInstallationOnlyAfterConfirmedOldRevocation() {
        var result = operations.register(admin, installationId, "replacement-meter", "Kitchen", now,
                "Old hardware buffer is irrecoverable; reviewed gap acknowledged");
        assertThat(result.device().id()).isNotEqualTo(deviceId);
        verify(store).endAssociation(deviceId, 1, now);
        verify(store).enqueue(argThat(job -> job.aggregateId().equals(deviceId) && job.payload().contains("SYNC_END")));
        assertThat(result.toString()).doesNotContain(result.oneTimeCredential());
    }

    @Test void hardwareReplacementCannotSilentlyDiscardAnUnconfirmedOldBuffer() {
        assertThatThrownBy(() -> operations.register(admin, installationId, "replacement-meter", "Kitchen", now))
                .isInstanceOf(DomainException.class);
        verify(store, never()).endAssociation(any(), anyLong(), any());
        verify(store, never()).saveDevice(any(), anyLong());
    }

    @Test void superAdminDeviceQueryUsesOperationalMetadataRatherThanConsumption() {
        when(store.listDevices(any(ListWindow.class),isNull())).thenReturn(List.of(revoked));
        when(telemetry.operationalStatus(eq(accountId), any())).thenReturn(List.of(new TelemetryGateway.OperationalStatus(deviceId, "DISCONNECTED", now)));
        assertThat(operations.query(admin, null).getFirst().connectionState()).isEqualTo("DISCONNECTED");
        verify(telemetry, never()).query(any());
    }

    @Test void ownerCannotPerformAdministrativeRevocation() {
        Actor owner = new Actor(accountId, Role.PROPIETARIO, Plan.PRO);
        assertThatThrownBy(() -> operations.revoke(owner, deviceId)).isInstanceOf(DomainException.class);
        verify(store, never()).lockDevice(any());
    }
}
