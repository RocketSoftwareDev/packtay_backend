package ec.paktay.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI authOpenApi() {
        return new OpenAPI().info(new Info().title("Paktay Auth API").version("v1")
                .description("Identidad, OAuth 2.0/OIDC y administración de usuarios."))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Token de acceso emitido por Keycloak. En Authorize pega únicamente el JWT, sin escribir el prefijo Bearer.")));
    }
}
