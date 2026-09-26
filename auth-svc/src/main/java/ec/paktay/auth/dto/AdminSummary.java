package ec.paktay.auth.dto;

/** Usuario con el rol ADMIN. */
public record AdminSummary(String id, String email, String name, boolean enabled) {
}
