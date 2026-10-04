package com.smartgn.management.configuration;

import com.smartgn.management.properties.application.PropertyService;
import com.smartgn.management.shared.application.port.out.ManagementStore;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import com.smartgn.management.application.support.SupportService;
import com.smartgn.management.application.reporting.ReportingService;
import com.smartgn.management.application.reporting.SnapshotCache;
import com.smartgn.management.adapter.out.cache.RedisSnapshotCache;
import com.smartgn.management.devices.application.DeviceOperations;
import com.smartgn.management.devices.application.OutboxOperations;
import com.smartgn.management.devices.application.DeviceProvisioningWorker;
import com.smartgn.management.installations.application.InstallationOperations;
import com.smartgn.management.integration.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class ApplicationConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean PropertyService propertyService(ManagementStore store, TransactionRunner transactions) {
        return new PropertyService(store, transactions);
    }
    @Bean SupportService supportService(ManagementStore store, TransactionRunner transactions, Clock clock) {
        return new SupportService(store, transactions, clock);
    }
    @Bean SnapshotCache snapshotCache(StringRedisTemplate redis, ObjectMapper mapper) { return new RedisSnapshotCache(redis, mapper); }
    @Bean ReportingService reportingService(ManagementStore store, TelemetryGateway telemetry, TransactionRunner transactions, Clock clock, SnapshotCache cache) {
        return new ReportingService(store, telemetry, transactions, clock, cache);
    }
    @Bean TelemetryGateway telemetryGateway(@Value("${smartgn.telemetry.base-url}") String url,
            @Value("${smartgn.telemetry.service-token}") String token, ObjectMapper mapper) {
        return new HttpTelemetryGateway(url, token, mapper);
    }
    @Bean BrokerGateway brokerGateway(@Value("${smartgn.broker.base-url}") String url,
            @Value("${smartgn.broker.api-key}") String key, @Value("${smartgn.broker.api-secret}") String secret, ObjectMapper mapper) {
        return new EmqxBrokerGateway(url, key, secret, mapper);
    }
    @Bean CredentialVault credentialVault(@Value("${smartgn.devices.credential-key}") String key) { return new CredentialVault(key); }
    @Bean InstallationOperations installationOperations(ManagementStore store, TransactionRunner transactions, Clock clock,DeviceOperations devices) {
        return new InstallationOperations(store, transactions, clock,devices);
    }
    @Bean DeviceOperations deviceOperations(ManagementStore store, TransactionRunner transactions, Clock clock, CredentialVault vault, TelemetryGateway telemetry, ObjectMapper mapper) {
        return new DeviceOperations(store, transactions, clock, vault, telemetry, mapper);
    }
    @Bean DeviceProvisioningWorker deviceProvisioningWorker(ManagementStore store, TransactionRunner transactions, Clock clock,
            CredentialVault vault, TelemetryGateway telemetry, BrokerGateway broker, ObjectMapper mapper) {
        return new DeviceProvisioningWorker(store, transactions, clock, vault, telemetry, broker, mapper);
    }
    @Bean OutboxOperations outboxOperations(ManagementStore store,TransactionRunner transactions,Clock clock,ObjectMapper mapper) {
        return new OutboxOperations(store,transactions,clock,mapper);
    }
    @Bean @ConditionalOnProperty(name="smartgn.worker.enabled", havingValue="true", matchIfMissing=true)
    ProvisioningSchedule provisioningSchedule(DeviceProvisioningWorker worker) { return new ProvisioningSchedule(worker); }
    static class ProvisioningSchedule {
        private final DeviceProvisioningWorker worker;
        ProvisioningSchedule(DeviceProvisioningWorker worker) { this.worker=worker; }
        @Scheduled(fixedDelayString="${smartgn.worker.delay-ms:1000}") void process() {
            try { worker.runOnce(); }
            catch (RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(ProvisioningSchedule.class).warn("Provisioning worker unavailable: {}",ex.getClass().getSimpleName()); }
        }
    }
}
