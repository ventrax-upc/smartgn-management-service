package com.smartgn.management.integration;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.ObjectMapper;

/** EMQX 5 administrative API adapter. Every mutation is safe to repeat. */
public final class EmqxBrokerGateway implements BrokerGateway {
    private static final String USERS = "/authentication/password_based%3Abuilt_in_database/users";
    private static final String RULES = "/authorization/sources/built_in_database/rules/users";
    private final String baseUrl;
    private final String authorization;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public EmqxBrokerGateway(String baseUrl, String apiKey, String apiSecret, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (apiKey + ":" + apiSecret).getBytes(StandardCharsets.UTF_8));
        this.mapper = mapper;
        // The EMQX 5.8 administration listener does not support Java's h2c upgrade reliably.
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override public void stage(UUID deviceId, String credential) {
        String user = deviceId.toString();
        deny(user);
        kick(user);
        Response current = exchange("GET", USERS + "/" + user, null);
        if (current.code() == 404) {
            Response created = exchange("POST", USERS,
                    Map.of("user_id", user, "password", credential, "is_superuser", false));
            if (created.code() == 409) require(exchange("PUT", USERS + "/" + user,
                    Map.of("password", credential, "is_superuser", false)));
            else require(created);
        } else {
            require(current);
            require(exchange("PUT", USERS + "/" + user,
                    Map.of("password", credential, "is_superuser", false)));
        }
    }

    @Override public void activate(UUID deviceId) {
        String user = deviceId.toString();
        rules(user, List.of(Map.of("topic", "smartgn/devices/" + user + "/telemetry",
                "permission", "allow", "action", "publish", "qos", List.of(1)),
                Map.of("topic", "#", "permission", "deny", "action", "all")));
        // Deployment disables the broker authorization cache. Stage already disconnected old sessions.
    }

    @Override public void revoke(UUID deviceId) {
        String user = deviceId.toString();
        deny(user);
        Response deleted = exchange("DELETE", USERS + "/" + user, null);
        if (deleted.code() != 404) require(deleted);
        kick(user);
    }

    private void deny(String user) {
        rules(user, List.of(Map.of("topic", "#", "permission", "deny", "action", "all")));
    }

    private void rules(String user, List<Map<String, Object>> rules) {
        Map<String, Object> payload = Map.of("username", user, "rules", rules);
        Response updated = exchange("PUT", RULES + "/" + user, payload);
        if (updated.code() == 404) {
            Response created = exchange("POST", RULES, List.of(payload));
            if (created.code() == 409) require(exchange("PUT", RULES + "/" + user, payload));
            else require(created);
        } else require(updated);
    }

    private void kick(String username) {
        String path = "/clients?username=" + encode(username) + "&limit=1000&page=1";
        Response response = exchange("GET", path, null);
        require(response);
        try {
            Map<?, ?> body = mapper.readValue(response.body(), Map.class);
            if (!(body.get("data") instanceof List<?> clients)) throw new DependencyFailure("MQTT broker");
            List<CompletableFuture<HttpResponse<String>>> disconnects = clients.stream().map(value -> {
                Map<?, ?> session = (Map<?, ?>) value;
                if (!(session.get("clientid") instanceof String id)) throw new DependencyFailure("MQTT broker");
                return client.sendAsync(request("DELETE", "/clients/" + encode(id), null),
                        HttpResponse.BodyHandlers.ofString());
            }).toList();
            CompletableFuture.allOf(disconnects.toArray(CompletableFuture[]::new)).join();
            for (var future : disconnects) {
                int status = future.join().statusCode();
                if (status != 404 && (status < 200 || status >= 300)) throw new DependencyFailure("MQTT broker");
            }
            Response checked = exchange("GET", path, null);
            require(checked);
            Map<?, ?> checkedBody = mapper.readValue(checked.body(), Map.class);
            if (!(checkedBody.get("data") instanceof List<?> remaining) || !remaining.isEmpty()) {
                throw new DependencyFailure("MQTT broker session revocation");
            }
        } catch (DependencyFailure e) {
            throw e;
        } catch (Exception e) {
            throw new DependencyFailure("MQTT broker");
        }
    }

    private HttpRequest request(String method, String path, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(2)).header("Authorization", authorization)
                .header("Content-Type", "application/json");
        String serialized = body == null ? "" : mapper.writeValueAsString(body);
        String correlation=org.slf4j.MDC.get("correlationId");
        if(correlation!=null) builder.header("X-Correlation-ID",correlation);
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(serialized)).build();
    }

    private Response exchange(String method, String path, Object body) {
        try {
            HttpResponse<String> response = client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 && response.statusCode() != 404) {
                org.slf4j.LoggerFactory.getLogger(EmqxBrokerGateway.class).warn(
                        "Broker administrative request rejected: method={} path={} status={}", method, path, response.statusCode());
            }
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyFailure("MQTT broker");
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(EmqxBrokerGateway.class).warn(
                    "Broker administrative transport failed: method={} path={} errorType={}", method, path, e.getClass().getSimpleName());
            throw new DependencyFailure("MQTT broker");
        }
    }

    private static void require(Response response) {
        if (response.code() < 200 || response.code() >= 300) throw new DependencyFailure("MQTT broker");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record Response(int code, String body) {}
}
