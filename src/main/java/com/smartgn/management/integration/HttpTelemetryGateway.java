package com.smartgn.management.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** HTTP adapter; the real Telemetry team must ratify the payload contract. */
public final class HttpTelemetryGateway implements TelemetryGateway {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String token;

    public HttpTelemetryGateway(String baseUrl, String token, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.token = token;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override public void synchronize(Association association) {
        Map<?, ?> result = post("/internal/v1/device-associations", association, Map.class);
        if (!association.deviceId().toString().equals(String.valueOf(result.get("deviceId")))
                || !(result.get("version") instanceof Number version)
                || version.longValue() != association.version()
                || !Boolean.TRUE.equals(result.get("confirmed"))) {
            throw new DependencyFailure("Telemetry association confirmation");
        }
    }

    @Override public BatchResult query(BatchQuery query) {
        BatchResult result = post("/internal/v1/telemetry/batch-query", query, BatchResult.class);
        if (result == null || result.metrics() == null || result.calculatedAt() == null
                || result.metrics().stream().anyMatch(m -> m == null || m.pointId() == null
                || !query.pointIds().contains(m.pointId()))) {
            throw new DependencyFailure("Telemetry batch query");
        }
        return result;
    }

    @Override public DrainResult drain(UUID deviceId, long associationVersion) {
        DrainResult result = post("/internal/v1/devices/" + deviceId + "/buffer-drain",
                Map.of("associationVersion", associationVersion), DrainResult.class);
        if (result == null || result.pendingReadings() < 0
                || (result.drained() && (result.pendingReadings() != 0
                || result.evidenceId() == null || result.evidenceId().isBlank()))) {
            throw new DependencyFailure("Telemetry buffer drain");
        }
        return result;
    }
    @Override public PeriodBatchResult queryPeriods(PeriodQuery query) {
        PeriodBatchResult result=post("/internal/v1/telemetry/periods-query",query,PeriodBatchResult.class);
        if(result==null||result.periods()==null) throw new DependencyFailure("Telemetry period query");
        return result;
    }

    @Override public List<OperationalStatus> operationalStatus(UUID accountId, List<UUID> deviceIds) {
        OperationalStatus[] response = post("/internal/v1/devices/status-query",
                Map.of("accountId", accountId, "deviceIds", deviceIds, "purpose", "DEVICE_OPERATIONS"), OperationalStatus[].class);
        if (response == null || java.util.Arrays.stream(response).anyMatch(value -> value == null || value.deviceId() == null
                || !deviceIds.contains(value.deviceId()) || value.connectionState() == null)) {
            throw new DependencyFailure("Telemetry operational metadata");
        }
        return List.of(response);
    }

    private <T> T post(String path, Object payload, Class<T> type) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            String correlation=org.slf4j.MDC.get("correlationId");
            if(correlation!=null) builder.header("X-Correlation-ID",correlation);
            HttpRequest request=builder.build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DependencyFailure("Telemetry");
            }
            return mapper.readValue(response.body(), type);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyFailure("Telemetry");
        } catch (Exception e) {
            throw new DependencyFailure("Telemetry");
        }
    }
}
