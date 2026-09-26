package ec.paktay.business.dto.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Detalle de un usuario en el panel: perfil, plan, uso del mes, dispositivos y consentimientos. */
public record AdminUserDetail(
        UUID id,
        String name,
        String email,
        String country,
        String plan,
        String planSource,
        String status,
        int activeCards,
        AdminUserSummary.LastAccess lastAccess,
        OffsetDateTime createdAt,
        String timezone,
        Subscription subscription,
        Usage usage,
        List<Device> devices,
        List<Consent> consents) {

    public record Subscription(String plan, String source, OffsetDateTime currentPeriodEnd) {
    }

    /** Límites null = sin límite (Pro y Duo). */
    public record Usage(int activeCards, Integer cardLimit, int walletCaptures, Integer captureLimit, int budgetCategories) {
    }

    public record Device(UUID id, String name, String platform, OffsetDateTime lastAuthenticatedAt,
                         boolean biometricEnabled, boolean pushEnabled) {
    }

    /** document: TERMS, PRIVACY o DUO_SHARING. */
    public record Consent(String document, String version, OffsetDateTime acceptedAt) {
    }
}
