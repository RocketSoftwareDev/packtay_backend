package ec.paktay.business.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI businessOpenApi() {
        return new OpenAPI().info(new Info().title("Paktay Business API").version("v1")
                .description("Lógica de negocio financiera. Las rutas autenticadas usan JWT de Keycloak; las rutas bajo /api/v1/public indican explícitamente que no requieren autenticación."))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Token de acceso emitido por Keycloak. En Authorize pega únicamente el JWT, sin escribir el prefijo Bearer.")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
