package com.smartgn.management.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean OpenAPI managementOpenApi() {
        return new OpenAPI().info(new Info().title("SmartGN Management API").version("1.0")
                .description("Property and supply-point management, verified technician directory, maintenance, IoT installations and devices, and authorized consumption reports. All administrative actions are audited. Device activation is asynchronous and requires confirmed broker provisioning and association synchronization."))
                .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Use an IAM-issued token with the same configured JWT secret. Roles: PROPIETARIO, ADMINISTRADOR, SUPERADMIN. Plans: FREE, PRO.")));
    }
}
