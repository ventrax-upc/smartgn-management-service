# SmartGN Management Service

SmartGN service for managing properties, supply points, technicians, maintenance, installations, and devices. It also supports viewing multiple meters and generating consumption reports with estimated costs.

User authentication is handled by the [IAM service](https://github.com/ventrax-upc/smartgn-iam-service).

## Architecture

Hexagonal architecture (ports and adapters), organized by business feature. Domain and application logic are separated from REST, database, cache, and external-service adapters. The service owns its PostgreSQL database and uses a transactional outbox for device provisioning.

## Technologies

- Java 21 and Spring Boot 4.1.1.
- Spring Web MVC for REST APIs and Spring Security with JWT authentication.
- PostgreSQL, Spring JDBC, and Flyway for persistence and database migrations.
- Optional Redis caching and adapters for EMQX administration and the Telemetry HTTP API.
- Maven, Docker, and OpenAPI/Swagger through springdoc.

## Project structure

```text
src/main/java/com/smartgn/management/  Application code, grouped by feature
src/main/resources/                  Configuration and database migrations
src/test/                            Automated tests and acceptance scenarios
docs/                                Architecture, API, and service documentation
scripts/                             Local setup and test scripts
tools/                               Integration checks and development simulator
pom.xml                              Project dependencies and build settings
compose.yml                          Local service configuration
Dockerfile                           Application container definition
```
