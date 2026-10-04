package com.smartgn.management.integration;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpTelemetryGatewayTest {
    @Test void associationAcknowledgementMustIdentifyTheExactDeviceAndVersion() throws Exception {
        UUID device = UUID.randomUUID();
        AtomicReference<String> response = new AtomicReference<>("{\"deviceId\":\"" + device + "\",\"version\":2,\"confirmed\":true}");
        HttpServer server = server(response);
        try {
            TelemetryGateway gateway = gateway(server);
            var association = new TelemetryGateway.Association(device, 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(), null);
            assertThatThrownBy(() -> gateway.synchronize(association)).isInstanceOf(DependencyFailure.class);
            response.set("{\"deviceId\":\"" + device + "\",\"version\":1,\"confirmed\":true}");
            assertThatCode(() -> gateway.synchronize(association)).doesNotThrowAnyException();
        } finally { server.stop(0); }
    }

    @Test void responseCannotIntroduceAPointOutsideTheRequestedScope() throws Exception {
        AtomicReference<String> response = new AtomicReference<>("{\"metrics\":[{\"pointId\":\"" + UUID.randomUUID()
                + "\",\"gap\":true}],\"calculatedAt\":\"2026-10-04T00:00:00Z\"}");
        HttpServer server = server(response);
        try {
            assertThatThrownBy(() -> gateway(server).query(new TelemetryGateway.BatchQuery(UUID.randomUUID(), List.of(UUID.randomUUID()), null, null)))
                    .isInstanceOf(DependencyFailure.class);
        } finally { server.stop(0); }
    }

    @Test void drainedBufferNeedsAnExplicitEvidenceIdentifierAndZeroPendingReadings() throws Exception {
        AtomicReference<String> response = new AtomicReference<>("{\"drained\":true,\"pendingReadings\":1,\"evidenceId\":\"evidence\"}");
        HttpServer server = server(response);
        try {
            assertThatThrownBy(() -> gateway(server).drain(UUID.randomUUID(), 1)).isInstanceOf(DependencyFailure.class);
            response.set("{\"drained\":true,\"pendingReadings\":0,\"evidenceId\":null}");
            assertThatThrownBy(() -> gateway(server).drain(UUID.randomUUID(), 1)).isInstanceOf(DependencyFailure.class);
            response.set("{\"drained\":false,\"pendingReadings\":0,\"evidenceId\":null}");
            assertThat(gateway(server).drain(UUID.randomUUID(), 1).drained()).isFalse();
        } finally { server.stop(0); }
    }

    @Test void nullEntriesFromAnExternalServiceProduceDependencyFailureInsteadOfAnUnexpectedError() throws Exception {
        AtomicReference<String> response = new AtomicReference<>("{\"metrics\":[null],\"calculatedAt\":\"2026-10-04T00:00:00Z\"}");
        HttpServer server = server(response);
        try {
            assertThatThrownBy(() -> gateway(server).query(new TelemetryGateway.BatchQuery(UUID.randomUUID(), List.of(UUID.randomUUID()), null, null)))
                    .isInstanceOf(DependencyFailure.class);
            response.set("[null]");
            assertThatThrownBy(() -> gateway(server).operationalStatus(UUID.randomUUID(), List.of(UUID.randomUUID())))
                    .isInstanceOf(DependencyFailure.class);
        } finally { server.stop(0); }
    }

    private static HttpTelemetryGateway gateway(HttpServer server) {
        return new HttpTelemetryGateway("http://127.0.0.1:" + server.getAddress().getPort(), "service-token", JsonMapper.builder().findAndAddModules().build());
    }
    @Test void serviceCallsPropagateTheCorrelationHeaderAndPeriodContract() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> header=new AtomicReference<>(),body=new AtomicReference<>();
        server.createContext("/internal/v1/telemetry/periods-query",exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] response="{\"periods\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();String correlation=UUID.randomUUID().toString();org.slf4j.MDC.put("correlationId",correlation);
        try {
            var query=new TelemetryGateway.PeriodQuery(UUID.randomUUID(),List.of(UUID.randomUUID()),List.of(
                    new TelemetryGateway.Period(Instant.parse("2026-10-01T00:00:00Z"),Instant.parse("2026-10-02T00:00:00Z"))));
            assertThat(gateway(server).queryPeriods(query).periods()).isEmpty();
            assertThat(header.get()).isEqualTo(correlation);assertThat(body.get()).contains("accountId","pointIds","periods");
        } finally {org.slf4j.MDC.remove("correlationId");server.stop(0);}
    }
    private static HttpServer server(AtomicReference<String> response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int status = "Bearer service-token".equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
