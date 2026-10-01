package ec.paktay.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({KeycloakProperties.class, SocialProperties.class})
public class HttpClientConfig {
    /** El de siempre; primario para que las inyecciones existentes por tipo no se vuelvan ambiguas. */
    @Bean
    @Primary
    RestClient keycloakRestClient(KeycloakProperties properties) {
        return RestClient.builder().baseUrl(properties.internalUrl()).build();
    }

    /** Día 8c · canje y revocación de Sign in with Apple. */
    @Bean
    RestClient appleRestClient() {
        return RestClient.builder().baseUrl("https://appleid.apple.com").build();
    }
}

