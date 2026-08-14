package ec.paktay.business.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component("keycloak")
public class KeycloakHealthIndicator implements HealthIndicator {
    private final RestClient restClient;

    public KeycloakHealthIndicator(
            @Value("${paktay.keycloak.health-url}") String healthUrl) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));

        this.restClient = RestClient.builder()
                .baseUrl(healthUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public Health health() {
        try {
            restClient.get()
                    .uri("/.well-known/openid-configuration")
                    .retrieve()
                    .toBodilessEntity();
            return Health.up().build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }
}
