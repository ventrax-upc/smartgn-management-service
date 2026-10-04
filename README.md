# SmartGN Management Service

SmartGN service for managing properties, supply points, technicians, maintenance, installations, and devices. It also supports viewing multiple meters and generating consumption reports with estimated costs.

User authentication is handled by the [IAM service](https://github.com/ventrax-upc/smartgn-iam-service).

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
