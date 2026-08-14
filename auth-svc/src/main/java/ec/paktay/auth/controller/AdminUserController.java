package ec.paktay.auth.controller;

import ec.paktay.auth.dto.AdminPasswordReplaceRequest;
import ec.paktay.auth.dto.MessageResponse;
import ec.paktay.auth.service.KeycloakIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Admin · Usuarios", description = "Administración de identidades; requiere rol ADMIN")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {
    private final KeycloakIdentityService identities;

    public AdminUserController(KeycloakIdentityService identities) {
        this.identities = identities;
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Eliminar un usuario")
    @ApiResponse(responseCode = "200", description = "Usuario eliminado")
    @ApiResponse(responseCode = "403", description = "Se requiere el rol ADMIN")
    public MessageResponse delete(@PathVariable String id) {
        identities.deleteUser(id);
        return new MessageResponse("Usuario eliminado");
    }

    @PutMapping("/{id}/password")
    @Operation(summary = "Reemplazar la contraseña de un usuario")
    @ApiResponse(responseCode = "200", description = "Contraseña reemplazada")
    @ApiResponse(responseCode = "403", description = "Se requiere el rol ADMIN")
    public MessageResponse replacePassword(@PathVariable String id, @Valid @RequestBody AdminPasswordReplaceRequest request) {
        identities.replacePassword(id, request.newPassword(), request.temporary());
        return new MessageResponse("Contraseña reemplazada");
    }
}
