package ec.paktay.business.service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Envía avisos por Firebase Cloud Messaging (API HTTP v1).
 *
 * La cuenta de servicio se lee de FIREBASE_CREDENTIALS_FILE. Si el archivo no existe o
 * está vacío (el compose monta /dev/null cuando no se configuró), los avisos quedan
 * apagados: el servicio arranca igual y sólo lo anota en el log. Así se prueba todo
 * lo demás sin Firebase.
 */
@Service
public class PushSender {
    private static final Logger log = LoggerFactory.getLogger(PushSender.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    public record Message(String title, String body, Map<String, String> data) { }

    /** Resultado por token: un token rechazado se tiene que olvidar. */
    public enum Outcome { SENT, INVALID_TOKEN, FAILED, DISABLED }

    private final GoogleCredentials credentials;
    private final String projectId;
    private final RestClient client;

    public PushSender(@Value("${paktay.push.credentials-file:}") String credentialsFile) {
        GoogleCredentials loaded = null;
        String project = null;
        try {
            Path path = credentialsFile == null || credentialsFile.isBlank() ? null : Path.of(credentialsFile);
            if (path != null && Files.isRegularFile(path) && Files.size(path) > 0) {
                try (InputStream input = new FileInputStream(path.toFile())) {
                    loaded = GoogleCredentials.fromStream(input).createScoped(List.of(SCOPE));
                }
                if (loaded instanceof ServiceAccountCredentials account) project = account.getProjectId();
            }
        } catch (IOException | RuntimeException ex) {
            log.error("push_credentials_invalid reason={}", ex.getClass().getSimpleName());
            loaded = null;
        }
        this.credentials = project == null ? null : loaded;
        this.projectId = project;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl("https://fcm.googleapis.com").requestFactory(factory).build();
        log.info("push_sender_ready enabled={}", enabled());
    }

    public boolean enabled() {
        return credentials != null;
    }

    public Outcome send(String token, Message message) {
        if (!enabled()) {
            log.info("push_skipped reason=not_configured title={}", message.title());
            return Outcome.DISABLED;
        }
        try {
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();
            Map<String, Object> body = Map.of("message", Map.of(
                    "token", token,
                    "notification", Map.of("title", message.title(), "body", message.body()),
                    "data", message.data() == null ? Map.of() : message.data(),
                    "apns", Map.of("payload", Map.of("aps", Map.of("sound", "default")))));
            client.post().uri("/v1/projects/{project}/messages:send", projectId)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
            return Outcome.SENT;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String response = ex.getResponseBodyAsString();
            // 404 UNREGISTERED: la app se desinstaló o el token caducó. 400 con
            // INVALID_ARGUMENT sobre el token: nunca fue válido. Los dos se olvidan.
            if (status == 404 || (status == 400 && response.contains("registration token"))) {
                return Outcome.INVALID_TOKEN;
            }
            log.warn("push_failed status={}", status);
            return Outcome.FAILED;
        } catch (IOException | RuntimeException ex) {
            log.warn("push_failed reason={}", ex.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }
}
