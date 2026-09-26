package ec.paktay.business.dto.support;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Ticket en la bandeja del panel. account no es null si el correo coincide con una cuenta. */
public record TicketSummary(
        UUID id,
        String code,
        String email,
        String reason,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime verifiedAt,
        boolean unread,
        TicketAccount account) {

    public record TicketAccount(UUID userId, String name, String plan, OffsetDateTime lastAccess) {
    }
}
