package ec.paktay.business.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paktay.supabase")
public record SupabaseStorageProperties(String url, String secretKey, String avatarBucket) {
}
