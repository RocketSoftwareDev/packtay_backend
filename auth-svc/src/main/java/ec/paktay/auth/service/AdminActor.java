package ec.paktay.auth.service;

import org.springframework.security.oauth2.jwt.Jwt;

/** Administrador que hace la acción: id de Keycloak y correo para la bitácora. */
public record AdminActor(String id, String label) {

    public static AdminActor from(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        String username = jwt.getClaimAsString("preferred_username");
        String label = email != null && !email.isBlank() ? email : username != null ? username : jwt.getSubject();
        return new AdminActor(jwt.getSubject(), label);
    }
}
