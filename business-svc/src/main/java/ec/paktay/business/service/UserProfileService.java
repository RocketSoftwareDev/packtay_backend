package ec.paktay.business.service;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import ec.paktay.business.config.SupabaseStorageProperties;
import ec.paktay.business.dto.UserProfileResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class UserProfileService {
    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of(
            MediaType.IMAGE_JPEG_VALUE, "jpg",
            MediaType.IMAGE_PNG_VALUE, "png",
            "image/webp", "webp");

    private final JdbcClient jdbc;
    private final UserAccountService users;
    private final RestClient storage;
    private final SupabaseStorageProperties properties;

    public UserProfileService(JdbcClient jdbc, UserAccountService users, RestClient supabaseStorageRestClient,
                              SupabaseStorageProperties properties) {
        this.jdbc = jdbc;
        this.users = users;
        this.storage = supabaseStorageRestClient;
        this.properties = properties;
    }

    @Transactional
    public UserProfileResponse get(UUID userId, String email, String displayName) {
        synchronizeIdentity(userId, email, displayName);
        return find(userId);
    }

    @Transactional
    public UserProfileResponse upload(UUID userId, String email, String displayName,
                                      String contentType, byte[] bytes) {
        if (!EXTENSIONS.containsKey(contentType)) {
            throw new IllegalArgumentException("La foto debe ser JPEG, PNG o WebP");
        }
        if (bytes.length == 0 || bytes.length > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("La foto debe pesar entre 1 byte y 5 MB");
        }
        validateConfiguration();
        synchronizeIdentity(userId, email, displayName);
        String previousPath = jdbc.sql("select avatar_object_path from app_users where id = :id")
                .param("id", userId).query(String.class).optional().orElse(null);
        String path = userId + "/avatar-" + UUID.randomUUID() + "." + EXTENSIONS.get(contentType);
        try {
            storage.put().uri("/storage/v1/object/{bucket}/{path}", properties.avatarBucket(), path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.secretKey())
                    .header("apikey", properties.secretKey())
                    .header("x-upsert", "false")
                    .contentType(MediaType.parseMediaType(contentType)).body(bytes).retrieve().toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("Supabase Storage rechazó la foto (HTTP " + ex.getStatusCode().value() + ")");
        }
        String publicUrl = properties.url() + "/storage/v1/object/public/" + properties.avatarBucket() + "/" + path;
        jdbc.sql("""
                update app_users set avatar_url = :url, avatar_object_path = :path,
                       avatar_updated_at = now() where id = :id
                """).param("url", publicUrl).param("path", path).param("id", userId).update();
        if (previousPath != null && !previousPath.equals(path)) deleteObject(previousPath);
        return find(userId);
    }

    @Transactional
    public UserProfileResponse remove(UUID userId, String email, String displayName) {
        synchronizeIdentity(userId, email, displayName);
        String path = jdbc.sql("select avatar_object_path from app_users where id = :id")
                .param("id", userId).query(String.class).optional().orElse(null);
        if (path != null) deleteObject(path);
        jdbc.sql("update app_users set avatar_url = null, avatar_object_path = null, avatar_updated_at = now() where id = :id")
                .param("id", userId).update();
        return find(userId);
    }

    @Transactional
    public UserProfileResponse updateAutomatic(UUID userId, String email, String displayName, boolean isAutomatic) {
        synchronizeIdentity(userId, email, displayName);
        jdbc.sql("update app_users set is_automatic = :isAutomatic where id = :id")
                .param("isAutomatic", isAutomatic).param("id", userId).update();
        return find(userId);
    }

    private void synchronizeIdentity(UUID userId, String email, String displayName) {
        users.ensureActiveUser(userId);
        jdbc.sql("update app_users set email = :email, display_name = :name where id = :id")
                .param("email", email).param("name", displayName).param("id", userId).update();
    }

    private UserProfileResponse find(UUID userId) {
        return jdbc.sql("""
                select u.id, u.email, u.display_name, u.avatar_url, u.is_automatic, u.avatar_updated_at,
                       exists(select 1 from cards c where c.user_id = u.id and c.status = 'ACTIVE') as is_have_cards,
                       exists(select 1 from user_categories uc where uc.user_id = u.id and uc.active) as is_have_category
                  from app_users u
                 where u.id = :id
                """)
                .param("id", userId).query((rs, rowNum) -> new UserProfileResponse(
                        rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("avatar_url"), rs.getBoolean("is_automatic"), rs.getBoolean("is_have_cards"),
                        rs.getBoolean("is_have_category"),
                        rs.getObject("avatar_updated_at", OffsetDateTime.class))).single();
    }

    private void deleteObject(String path) {
        validateConfiguration();
        try {
            storage.delete().uri(URI.create(properties.url() + "/storage/v1/object/" + properties.avatarBucket() + "/" + path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.secretKey())
                    .header("apikey", properties.secretKey()).retrieve().toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("No fue posible eliminar la foto anterior de Supabase Storage");
        }
    }

    private void validateConfiguration() {
        if (properties.url() == null || properties.secretKey() == null || properties.avatarBucket() == null) {
            throw new IllegalStateException("Supabase Storage no está configurado");
        }
    }
}
