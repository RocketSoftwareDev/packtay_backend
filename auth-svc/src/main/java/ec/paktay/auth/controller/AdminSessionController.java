package ec.paktay.auth.controller;

import ec.paktay.auth.dto.AdminLoginRequest;
import ec.paktay.auth.dto.AdminRefreshRequest;
import ec.paktay.auth.dto.TokenResponse;
import ec.paktay.auth.service.AdminSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sesión del panel. Rutas públicas (sin token) pero con la cabecera X-Paktay-Client; las llama solo
 * el servidor de la web (BFF), que guarda los tokens en una cookie HttpOnly.
 */
@RestController
@RequestMapping("/api/v1/admin/session")
@Tag(name = "Admin · Sesión del panel", description = "Login propio del panel con rol ADMIN. Rutas públicas: no requieren token, sí la cabecera X-Paktay-Client.")
public class AdminSessionController {
    private final AdminSessionService sessions;

    public AdminSessionController(AdminSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesión en el panel", description = "Ruta pública. Exige el rol ADMIN. Contraseña mala, cuenta sin "
            + "ADMIN, bloqueada o con demasiados intentos reciben la misma respuesta. El token solo sirve para /api/v1/admin/**.")
    @ApiResponse(responseCode = "200", description = "Tokens del panel (access 15 min)")
    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS")
    @ApiResponse(responseCode = "403", description = "PASSWORD_CHANGE_REQUIRED (contraseña temporal) o ADMIN_CLIENT_REQUIRED (sin cabecera)")
    public TokenResponse login(@Valid @RequestBody AdminLoginRequest request) {
        return sessions.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renovar la sesión del panel", description = "Ruta pública. Vuelve a exigir el rol ADMIN: si se lo quitaron, la sesión termina.")
    @ApiResponse(responseCode = "200", description = "Tokens renovados")
    @ApiResponse(responseCode = "401", description = "SESSION_EXPIRED")
    public TokenResponse refresh(@Valid @RequestBody AdminRefreshRequest request) {
        return sessions.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cerrar la sesión del panel", description = "Ruta pública. Cierra la sesión en el proveedor de identidad; es idempotente.")
    @ApiResponse(responseCode = "204", description = "Sesión cerrada")
    public void logout(@Valid @RequestBody AdminRefreshRequest request) {
        sessions.logout(request.refreshToken());
    }
}
