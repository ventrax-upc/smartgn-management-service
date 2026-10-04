package com.smartgn.management.adapter.out.cache;

import com.smartgn.management.application.reporting.SnapshotCache;
import com.smartgn.management.integration.TelemetryGateway.BatchResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;

/** A disposable cache. Authorization is always checked by ReportingService first. */
public final class RedisSnapshotCache implements SnapshotCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    public RedisSnapshotCache(StringRedisTemplate redis, ObjectMapper mapper) { this.redis = redis; this.mapper = mapper; }
    @Override public Optional<BatchResult> get(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, BatchResult.class));
        } catch (RuntimeException unavailable) { return Optional.empty(); }
    }
    @Override public void put(String key, BatchResult value, Duration ttl) {
        try { redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl); }
        catch (RuntimeException unavailable) { /* The source remains authoritative when the cache fails. */ }
    }
}
