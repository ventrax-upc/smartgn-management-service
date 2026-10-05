package com.smartgn.management.devices.application;

import com.smartgn.management.integration.CredentialVault;
import com.smartgn.management.integration.DependencyFailure;
import com.smartgn.management.integration.TelemetryGateway;
import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.DomainException;
import com.smartgn.management.shared.domain.Plan;
import com.smartgn.management.shared.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DisabledDeviceOperationsTest {
    private final ManagementStore store = mock(ManagementStore.class);
    private final TransactionRunner transactions = mock(TransactionRunner.class);
    private final TelemetryGateway telemetry = mock(TelemetryGateway.class);
    private final DeviceOperations operations = new DeviceOperations(store, transactions, Clock.systemUTC(),
            new CredentialVault(Base64.getEncoder().encodeToString(new byte[32])), telemetry,
            JsonMapper.builder().build(), false);
    private final Actor admin = new Actor(UUID.randomUUID(), Role.SUPERADMIN, Plan.FREE);

    @Test void disabledProvisioningCannotGenerateJobsOrModifyDeviceState() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> operations.register(admin, id, "meter", "Kitchen", Instant.now()))
                .isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> operations.rotate(admin, id)).isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> operations.revoke(admin, id)).isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> operations.reassociate(admin, id, id, true, "Reviewed gap"))
                .isInstanceOf(DependencyFailure.class);
        verifyNoInteractions(store, transactions, telemetry);
    }

    @Test void permissionChecksStillPrecedeIntegrationAvailability() {
        var owner = new Actor(UUID.randomUUID(), Role.PROPIETARIO, Plan.FREE);
        assertThatThrownBy(() -> operations.revoke(owner, UUID.randomUUID())).isInstanceOf(DomainException.class);
        verifyNoInteractions(store, transactions, telemetry);
    }
}
