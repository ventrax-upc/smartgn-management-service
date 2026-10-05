package com.smartgn.management.configuration;

import com.smartgn.management.ManagementApplication;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises Render defaults against an isolated local PostgreSQL schema, without external services. */
@SpringBootTest(classes = ManagementApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("render")
@EnabledIfEnvironmentVariable(named = "SMARTGN_TEST_DATABASE_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RenderProfileDatabaseTest {
    private static final String SCHEMA = "management_render_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String SECRET = "render-test-secret-0123456789-abcdefghijklmnop";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", () -> System.getenv("SMARTGN_TEST_DATABASE_URL"));
        values.add("spring.datasource.username", RenderProfileDatabaseTest::databaseUser);
        values.add("spring.datasource.password", RenderProfileDatabaseTest::databasePassword);
        values.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        values.add("spring.flyway.schemas", () -> SCHEMA);
        values.add("spring.flyway.default-schema", () -> SCHEMA);
        values.add("smartgn.security.jwt-secret", () -> SECRET);
        values.add("smartgn.devices.credential-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        values.add("spring.data.redis.port", () -> 1);
    }

    @AfterAll static void cleanupSchema() {
        if (!SCHEMA.matches("management_render_test_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe test schema");
        var database = new DriverManagerDataSource(System.getenv("SMARTGN_TEST_DATABASE_URL"), databaseUser(), databasePassword());
        new JdbcTemplate(database).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test void healthAndHttpsSwaggerWorkWithoutRedisBrokerOrTelemetry() throws Exception {
        var health = send("GET", "/actuator/health", null, null);
        assertEquals(200, health.statusCode(), health.body());
        assertEquals("UP", mapper.readTree(health.body()).get("status").asText());
        var swagger = client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/v3/api-docs"))
                .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "management.example")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, swagger.statusCode(), swagger.body());
        assertEquals("https://management.example", mapper.readTree(swagger.body()).get("servers").get(0).get("url").asText());
    }

    @Test void databaseBackedPropertyOperationsRemainAvailable() throws Exception {
        String token = token("PROPIETARIO");
        var response = send("POST", "/api/v1/properties", token,
                "{\"name\":\"Render test home\",\"address\":\"Test Street 123\",\"propertyType\":\"HOUSE\"}");
        assertEquals(201, response.statusCode(), response.body());
        String id = mapper.readTree(response.body()).get("id").asText();
        assertEquals(200, send("GET", "/api/v1/properties/" + id, token, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/properties", null, null).statusCode());
    }

    @Test void disabledDeviceProvisioningReturns503WithoutPersistingDevicesOrJobs() throws Exception {
        var response = send("POST", "/api/v1/devices", token("SUPERADMIN"),
                "{\"installationId\":\"" + UUID.randomUUID() + "\",\"serialNumber\":\"render-test-meter\","
                + "\"location\":\"Kitchen\",\"installedAt\":\"" + Instant.now().minusSeconds(60) + "\"}");
        assertEquals(503, response.statusCode(), response.body());
        assertEquals("DEPENDENCY_UNAVAILABLE", mapper.readTree(response.body()).get("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM devices", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_jobs", Integer.class));
    }

    private static String token(String role) {
        String account = UUID.randomUUID().toString();
        return Jwts.builder().subject(account).issuer("smartgn-iam-service")
                .issuedAt(Date.from(Instant.now())).expiration(Date.from(Instant.now().plusSeconds(300)))
                .claim("accountId", account).claim("role", role).claim("plan", "FREE")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String baseUrl() { return "http://127.0.0.1:" + port; }
    private static String databaseUser() { return Objects.requireNonNullElse(System.getenv("SMARTGN_TEST_DATABASE_USERNAME"), "postgres"); }
    private static String databasePassword() { return Objects.requireNonNullElse(System.getenv("SMARTGN_TEST_DATABASE_PASSWORD"), ""); }
}
