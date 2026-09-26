package ec.paktay.business.dto.support;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Ticket con su conversación. author de cada mensaje: USER, ADMIN, NOTE (interna) o SYSTEM. */
public record TicketDetail(
        UUID id,
        String code,
        String email,
        String reason,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime verifiedAt,
        boolean unread,
        TicketSummary.TicketAccount account,
        List<Message> messages) {

    public record Message(UUID id, String author, String authorName, String body, OffsetDateTime createdAt) {
    }

    public static TicketDetail of(TicketSummary ticket, List<Message> messages) {
        return new TicketDetail(ticket.id(), ticket.code(), ticket.email(), ticket.reason(), ticket.status(),
                ticket.createdAt(), ticket.verifiedAt(), ticket.unread(), ticket.account(), messages);
    }
}
