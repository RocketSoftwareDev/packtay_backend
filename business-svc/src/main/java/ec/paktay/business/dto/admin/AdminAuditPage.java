package ec.paktay.business.dto.admin;

import java.util.List;
import java.util.Map;

/** Eventos filtrados y conteo por tipo (ALL, USER_CREATED, ADMIN_ACTION, ACCOUNT_DELETED) en el mismo período. */
public record AdminAuditPage(List<AdminAuditEvent> items, Map<String, Long> counts) {
}
