package com.smartgn.management.integration;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnavailableIntegrationsTest {
    @Test void disabledTelemetryNeverReturnsInventedReadingsOrDrainConfirmations() {
        var gateway = UnavailableIntegrations.telemetry();
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> gateway.query(new TelemetryGateway.BatchQuery(id, List.of(id), null, null)))
                .isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> gateway.queryPeriods(new TelemetryGateway.PeriodQuery(id, List.of(id), List.of())))
                .isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> gateway.drain(id, 1)).isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> gateway.operationalStatus(id, List.of(id))).isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> gateway.synchronize(new TelemetryGateway.Association(id, 1, id, id, id, null, null)))
                .isInstanceOf(DependencyFailure.class);
    }

    @Test void disabledBrokerCannotConfirmActivationOrRevocation() {
        var gateway = UnavailableIntegrations.broker();
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> gateway.stage(id, "test-credential")).isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> gateway.activate(id)).isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> gateway.revoke(id)).isInstanceOf(DependencyFailure.class);
    }
}
