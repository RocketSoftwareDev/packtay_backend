package ec.paktay.auth.controller;

import ec.paktay.auth.dto.AdminPasswordReplaceRequest;
import ec.paktay.auth.dto.MessageResponse;
import ec.paktay.auth.service.KeycloakIdentityService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {
    private final KeycloakIdentityService identities;

    public AdminUserController(KeycloakIdentityService identities) {
        this.identities = identities;
    }

    @DeleteMapping("/{id}")
    public MessageResponse delete(@PathVariable String id) {
        identities.deleteUser(id);
        return new MessageResponse("Usuario eliminado");
    }

    @PutMapping("/{id}/password")
    public MessageResponse replacePassword(@PathVariable String id, @Valid @RequestBody AdminPasswordReplaceRequest request) {
        identities.replacePassword(id, request.newPassword(), request.temporary());
        return new MessageResponse("Contraseña reemplazada");
    }
}

