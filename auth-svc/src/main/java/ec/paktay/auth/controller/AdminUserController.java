package ec.paktay.auth.controller;

import java.util.List;

import ec.paktay.auth.dto.AdminSummary;
import ec.paktay.auth.dto.MessageResponse;
import ec.paktay.auth.service.AccountDeletionService;
import ec.paktay.auth.service.AdminAccountService;
import ec.paktay.auth.service.AdminActor;
import ec.paktay.auth.service.TemporaryPasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin · Cuentas y roles", description = "Acciones que tocan Keycloak: bloqueo, contraseña, borrado y rol ADMIN. "
        + "Requiere rol ADMIN; todas quedan en la bitácora del panel.")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {
    private final AdminAccountService accounts;
    private final AccountDeletionService deletions;
    private final TemporaryPasswordService temporaryPasswords;

    public AdminUserController(AdminAccountService accounts, AccountDeletionService deletions,
                               TemporaryPasswordService temporaryPasswords) {
        this.accounts = accounts;
        this.deletions = deletions;
        this.temporaryPasswords = temporaryPasswords;
    }

    @DeleteMapping("/users/{id}")
    @Operation(summary = "Eliminar cuenta y datos", description = "Purga todos los datos (purge_user, incluidos sus tickets) y borra "
            + "la identidad en Keycloak. Igual que el borrado desde la app, sin contraseña. Queda el cierre anónimo en Auditoría.")
    @ApiResponse(responseCode = "200", description = "Cuenta eliminada")
    @ApiResponse(responseCode = "403", description = "Se requiere el rol ADMIN")
    @ApiResponse(responseCode = "502", description = "Los datos se borraron pero Keycloak no respondió; repetir")
    public MessageResponse delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        deletions.deleteByAdmin(id, AdminActor.from(jwt));
        return new MessageResponse("Cuenta eliminada");
    }

    @PostMapping("/users/{id}/password/temporary")
    @Operation(summary = "Enviar contraseña temporal", description = "Genera una contraseña temporal (24 h), la manda al correo de la "
            + "cuenta, cierra sus sesiones y quita el bloqueo por intentos. El administrador no la ve. Al entrar al móvil con ella, "
            + "la app obliga a elegir una nueva.")
    @ApiResponse(responseCode = "200", description = "Contraseña enviada")
    @ApiResponse(responseCode = "404", description = "No existe")
    @ApiResponse(responseCode = "409", description = "Cuenta propia, bloqueada o sin correo")
    @ApiResponse(responseCode = "502", description = "La contraseña se cambió pero el correo no salió; repetir")
    public MessageResponse sendTemporaryPassword(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        temporaryPasswords.issue(id, AdminActor.from(jwt));
        return new MessageResponse("Contraseña temporal enviada");
    }

    @PostMapping("/users/{id}/block")
    @Operation(summary = "Bloquear cuenta", description = "Desactiva la identidad, cierra sus sesiones y marca app_users INACTIVE. "
            + "La app recibe 403 ACCOUNT_BLOCKED. No borra nada. No se puede bloquear la cuenta propia.")
    @ApiResponse(responseCode = "200", description = "Cuenta bloqueada")
    @ApiResponse(responseCode = "409", description = "Es tu propia cuenta")
    public MessageResponse block(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        accounts.block(id, AdminActor.from(jwt));
        return new MessageResponse("Cuenta bloqueada");
    }

    @PostMapping("/users/{id}/unblock")
    @Operation(summary = "Desbloquear cuenta")
    @ApiResponse(responseCode = "200", description = "Cuenta desbloqueada")
    public MessageResponse unblock(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        accounts.unblock(id, AdminActor.from(jwt));
        return new MessageResponse("Cuenta desbloqueada");
    }

    @GetMapping("/admins")
    @Operation(summary = "Listar administradores", description = "Usuarios con el rol ADMIN de Keycloak.")
    @ApiResponse(responseCode = "200", description = "Administradores")
    public List<AdminSummary> admins() {
        return accounts.admins();
    }

    @PutMapping("/users/{id}/roles/admin")
    @Operation(summary = "Asignar el rol ADMIN", description = "El usuario podrá entrar al panel en su próximo inicio de sesión.")
    @ApiResponse(responseCode = "200", description = "Rol asignado")
    @ApiResponse(responseCode = "404", description = "No existe")
    public MessageResponse grantAdmin(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        accounts.grantAdmin(id, AdminActor.from(jwt));
        return new MessageResponse("Rol ADMIN asignado");
    }

    @DeleteMapping("/users/{id}/roles/admin")
    @Operation(summary = "Quitar el rol ADMIN", description = "No se puede quitar a uno mismo ni al último administrador activo. "
            + "El token que ya tenga sigue valiendo hasta que venza (15 minutos en el panel).")
    @ApiResponse(responseCode = "200", description = "Rol quitado")
    @ApiResponse(responseCode = "409", description = "Es tu propia cuenta o el último administrador")
    public MessageResponse revokeAdmin(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        accounts.revokeAdmin(id, AdminActor.from(jwt));
        return new MessageResponse("Rol ADMIN quitado");
    }
}
