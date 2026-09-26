package ec.paktay.business.dto.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Evento de la bitácora del panel. kind: USER_CREATED, ADMIN_ACTION o ACCOUNT_DELETED.
 * detail es el sujeto legible; en ACCOUNT_DELETED es un id anónimo, nunca el correo.
 */
public record AdminAuditEvent(
        UUID id,
        String kind,
        String action,
        String actor,
        String detail,
        OffsetDateTime createdAt,
        JsonNode before,
        JsonNode after) {
}
