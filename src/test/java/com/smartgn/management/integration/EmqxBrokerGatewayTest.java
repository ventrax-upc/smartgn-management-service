package com.smartgn.management.integration;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EmqxBrokerGatewayTest {
    @Test void provisioningAndRotationUseDenyAclAndIdempotentCredentialUpsertsThenRevocationKicksSessions() throws Exception {
        UUID deviceId = UUID.randomUUID();
        AtomicBoolean userExists = new AtomicBoolean();
        AtomicBoolean rulesExist = new AtomicBoolean();
        AtomicBoolean connected = new AtomicBoolean(true);
        List<String> steps = java.util.Collections.synchronizedList(new ArrayList<>());
        var mapper = JsonMapper.builder().findAndAddModules().build();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 204;
            String result = "";
            if (path.contains("/authorization/")) {
                if (method.equals("PUT") && !rulesExist.get()) status = 404;
                else if (method.equals("POST")) {
                    assertThat(mapper.readTree(body).isArray()).isTrue();
                    rulesExist.set(true);
                    steps.add("DENY");
                } else steps.add(body.contains("\"allow\"") ? "ALLOW" : "DENY");
            } else if (path.endsWith("/clients") && method.equals("GET")) {
                status = 200;
                result = connected.get() ? "{\"data\":[{\"clientid\":\"live-session\"}]}" : "{\"data\":[]}";
            } else if (path.endsWith("/clients/live-session") && method.equals("DELETE")) {
                connected.set(false); steps.add("KICK");
            } else if (path.contains("/authentication/")) {
                if (method.equals("GET")) { status = userExists.get() ? 200 : 404; result = "{}"; }
                else if (method.equals("POST")) { status = 201; userExists.set(true); steps.add("CREATE"); }
                else if (method.equals("PUT")) steps.add("ROTATE");
                else if (method.equals("DELETE")) { userExists.set(false); steps.add("DELETE"); }
            }
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            BrokerGateway broker = new EmqxBrokerGateway("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v5", "key", "secret", mapper);
            broker.stage(deviceId, "first-password");
            assertThat(steps).containsSubsequence("DENY", "KICK", "CREATE").doesNotContain("ALLOW");
            broker.stage(deviceId, "rotated-password");
            assertThat(steps).contains("ROTATE").doesNotContain("ALLOW");
            broker.activate(deviceId);
            assertThat(steps.getLast()).isEqualTo("ALLOW");
            connected.set(true);
            broker.revoke(deviceId);
            assertThat(steps).containsSubsequence("ALLOW", "DENY", "DELETE", "KICK");
            assertThat(connected).isFalse();
            assertThat(userExists).isFalse();
        } finally { server.stop(0); }
    }
}
