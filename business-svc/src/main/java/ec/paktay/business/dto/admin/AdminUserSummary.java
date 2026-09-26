package ec.paktay.business.dto.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Fila de la lista de usuarios del panel. Sin montos ni categorías.
 * planSource null = sin suscripción (en la beta equivale a PRO).
 * status: ACTIVE o BLOCKED (app_users.status INACTIVE).
 */
public record AdminUserSummary(
        UUID id,
        String name,
        String email,
        String country,
        String plan,
        String planSource,
        String status,
        int activeCards,
        LastAccess lastAccess,
        OffsetDateTime createdAt) {

    public record LastAccess(String device, OffsetDateTime at) {
    }
}
