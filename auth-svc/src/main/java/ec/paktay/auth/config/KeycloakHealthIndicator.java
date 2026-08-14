package ec.paktay.auth.config;

import java.time.Duration;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component("keycloak")
public class KeycloakHealthIndicator implements HealthIndicator {
    private final RestClient restClient;
    private final String realm;

    public KeycloakHealthIndicator(KeycloakProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));

        this.restClient = RestClient.builder()
                .baseUrl(properties.internalUrl())
                .requestFactory(requestFactory)
                .build();
        this.realm = properties.realm();
    }

    @Override
    public Health health() {
        try {
            restClient.get()
                    .uri("/realms/{realm}/.well-known/openid-configuration", realm)
                    .retrieve()
                    .toBodilessEntity();
            return Health.up().build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }
}
