package ec.paktay.business.dto.admin;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/** Administrador que hace la acción, tomado del token: id de Keycloak y correo para la bitácora. */
public record AdminActor(UUID id, String label) {

    public static AdminActor from(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        String username = jwt.getClaimAsString("preferred_username");
        String label = email != null && !email.isBlank() ? email : username != null ? username : jwt.getSubject();
        return new AdminActor(UUID.fromString(jwt.getSubject()), label);
    }
}
