package com.smartgn.management.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Port for the provisional, versioned Telemetry service contract. */
public interface TelemetryGateway {
    record Association(UUID deviceId, long version, UUID accountId, UUID propertyId,
                       UUID pointId, Instant validFrom, Instant validTo) {}
    record BatchQuery(UUID accountId, List<UUID> pointIds, Instant from, Instant to) {}
    record Metric(UUID pointId, BigDecimal volumeM3, BigDecimal pressureKpa,
                  Boolean gasDetected, String valveState, String connectionState,
                  String validityState, Instant measuredAt, Instant calculatedAt, boolean gap) {}
    record BatchResult(List<Metric> metrics, Instant calculatedAt) {}
    record Period(Instant from, Instant to) {}
    record PeriodQuery(UUID accountId, List<UUID> pointIds, List<Period> periods) {}
    record PeriodResult(Instant from, Instant to, BatchResult snapshot) {}
    record PeriodBatchResult(List<PeriodResult> periods) {}
    record DrainResult(boolean drained, int pendingReadings, String evidenceId) {}
    record OperationalStatus(UUID deviceId, String connectionState, Instant lastContact) {}

    void synchronize(Association association);
    BatchResult query(BatchQuery query);
    /** Exact interval aggregates: never allocate a whole-period volume proportionally to time. */
    default PeriodBatchResult queryPeriods(PeriodQuery query) {
        return new PeriodBatchResult(query.periods().stream().map(period -> new PeriodResult(period.from(),period.to(),
                query(new BatchQuery(query.accountId(),query.pointIds(),period.from(),period.to())))).toList());
    }
    DrainResult drain(UUID deviceId, long associationVersion);
    /** Administrative metadata only; never returns consumption or safety measurements. */
    default List<OperationalStatus> operationalStatus(UUID accountId, List<UUID> deviceIds) { return List.of(); }
}
