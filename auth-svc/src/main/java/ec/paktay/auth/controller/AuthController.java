package ec.paktay.auth.controller;

import ec.paktay.auth.config.KeycloakProperties;
import ec.paktay.auth.dto.LoginRequest;
import ec.paktay.auth.dto.MessageResponse;
import ec.paktay.auth.dto.OAuthConfigResponse;
import ec.paktay.auth.dto.PasswordChangeRequest;
import ec.paktay.auth.dto.RegisterRequest;
import ec.paktay.auth.dto.TokenResponse;
import ec.paktay.auth.dto.UserResponse;
import ec.paktay.auth.service.KeycloakIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticación", description = "Rutas públicas de registro e inicio de sesión y operaciones autenticadas de la cuenta")
public class AuthController {
    private final KeycloakIdentityService identities;
    private final KeycloakProperties properties;

    public AuthController(KeycloakIdentityService identities, KeycloakProperties properties) {
        this.identities = identities;
        this.properties = properties;
    }

    @GetMapping("/oauth-config")
    @Operation(summary = "Obtener configuración OAuth móvil", description = "Ruta pública; no requiere token.")
    public OAuthConfigResponse oauthConfig() {
        return new OAuthConfigResponse(properties.publicUrl() + "/realms/" + properties.realm(), properties.mobileClientId(), "authorization_code", "S256");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar usuario", description = "Ruta pública; no requiere token.")
    @ApiResponse(responseCode = "201", description = "Usuario registrado")
    @ApiResponse(responseCode = "400", description = "Datos inválidos")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return identities.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesión", description = "Ruta pública de apoyo para Postman y pruebas; no requiere token.")
    @ApiResponse(responseCode = "200", description = "Tokens emitidos")
    @ApiResponse(responseCode = "401", description = "Credenciales inválidas")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return identities.login(request);
    }

    @PutMapping("/password")
    @Operation(summary = "Cambiar mi contraseña")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Contraseña actualizada")
    @ApiResponse(responseCode = "401", description = "Token o contraseña actual inválidos")
    public MessageResponse changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordChangeRequest request) {
        String username = jwt.getClaimAsString("preferred_username");
        identities.verifyCredentials(username, request.currentPassword());
        identities.replacePassword(jwt.getSubject(), request.newPassword(), false);
        return new MessageResponse("Contraseña actualizada");
    }
}
