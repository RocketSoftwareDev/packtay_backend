package ec.paktay.business.controller;

import java.util.UUID;

import ec.paktay.business.dto.ShortcutCredentialResponse;
import ec.paktay.business.service.ShortcutCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/shortcut-credential")
@Tag(name = "Usuario · Conexión de Apple Shortcut", description = "Crea y revoca la credencial personal que conecta una automatización de Wallet con la cuenta autenticada")
@SecurityRequirement(name = "bearerAuth")
public class ShortcutCredentialController {
    private final ShortcutCredentialService credentials;

    public ShortcutCredentialController(ShortcutCredentialService credentials) {
        this.credentials = credentials;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Generar un código de conexión", description = "Ruta autenticada con bearerAuth. Revoca el código anterior y entrega el secreto nuevo una sola vez.")
    @ApiResponse(responseCode = "201", description = "Código generado y vinculado al usuario")
    @ApiResponse(responseCode = "401", description = "Sesión de usuario ausente o inválida")
    public ShortcutCredentialResponse rotate(@AuthenticationPrincipal Jwt jwt) {
        return credentials.rotate(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping
    @Operation(summary = "Consultar la conexión", description = "Ruta autenticada con bearerAuth. Nunca vuelve a entregar el secreto; muestra estado, pista y último uso.")
    @ApiResponse(responseCode = "200", description = "Estado actual de la conexión")
    public ShortcutCredentialResponse status(@AuthenticationPrincipal Jwt jwt) {
        return credentials.status(UUID.fromString(jwt.getSubject()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desconectar Apple Shortcut", description = "Ruta autenticada con bearerAuth. Revoca inmediatamente el código activo del usuario.")
    @ApiResponse(responseCode = "204", description = "Conexión revocada")
    public void revoke(@AuthenticationPrincipal Jwt jwt) {
        credentials.revoke(UUID.fromString(jwt.getSubject()));
    }
}
