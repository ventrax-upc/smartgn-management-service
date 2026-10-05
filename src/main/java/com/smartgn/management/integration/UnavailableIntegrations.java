package com.smartgn.management.integration;

import java.util.List;
import java.util.UUID;

/** Explicitly unavailable ports for deployments without the corresponding services. */
public final class UnavailableIntegrations {
    private UnavailableIntegrations() {}

    public static TelemetryGateway telemetry() {
        return new TelemetryGateway() {
            @Override public void synchronize(Association association) { throw unavailable(); }
            @Override public BatchResult query(BatchQuery query) { throw unavailable(); }
            @Override public PeriodBatchResult queryPeriods(PeriodQuery query) { throw unavailable(); }
            @Override public DrainResult drain(UUID deviceId, long associationVersion) { throw unavailable(); }
            @Override public List<OperationalStatus> operationalStatus(UUID accountId, List<UUID> deviceIds) {
                throw unavailable();
            }
            private DependencyFailure unavailable() { return new DependencyFailure("Telemetry"); }
        };
    }

    public static BrokerGateway broker() {
        return new BrokerGateway() {
            @Override public void stage(UUID deviceId, String credential) { throw unavailable(); }
            @Override public void activate(UUID deviceId) { throw unavailable(); }
            @Override public void revoke(UUID deviceId) { throw unavailable(); }
            private DependencyFailure unavailable() { return new DependencyFailure("EMQX"); }
        };
    }
}
