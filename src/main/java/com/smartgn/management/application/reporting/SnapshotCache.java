package com.smartgn.management.application.reporting;

import com.smartgn.management.integration.TelemetryGateway.BatchResult;
import java.time.Duration;
import java.util.Optional;

/** Disposable cache. Implementations must fail open to the persistent owner. */
public interface SnapshotCache {
    Optional<BatchResult> get(String key);
    void put(String key, BatchResult value, Duration ttl);

    static SnapshotCache disabled() {
        return new SnapshotCache() {
            public Optional<BatchResult> get(String key) { return Optional.empty(); }
            public void put(String key, BatchResult value, Duration ttl) {}
        };
    }
}
