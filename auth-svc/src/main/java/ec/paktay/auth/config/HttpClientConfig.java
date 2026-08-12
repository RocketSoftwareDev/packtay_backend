package ec.paktay.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(KeycloakProperties.class)
public class HttpClientConfig {
    @Bean
    RestClient keycloakRestClient(KeycloakProperties properties) {
        return RestClient.builder().baseUrl(properties.internalUrl()).build();
    }
}

