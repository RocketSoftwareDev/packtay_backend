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
    private final ec.paktay.auth.service.PasswordPinService pins;

    public AuthController(KeycloakIdentityService identities, KeycloakProperties properties, ec.paktay.auth.service.PasswordPinService pins) {
        this.pins = pins;
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
    @Operation(summary = "Cambiar mi contraseña con PIN", description = "Paso 3. Requiere resetToken obtenido al validar el PIN; vence en 10 minutos y es de un solo uso.")
    @ApiResponse(responseCode = "400", description = "Validación inválida, expirada o contraseña inválida")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Contraseña actualizada")
    @ApiResponse(responseCode = "401", description = "Token inválido")
    public MessageResponse changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordChangeRequest request) {
        pins.completeChange(jwt.getSubject(), request.resetToken(), request.newPassword());
        return new MessageResponse("Contraseña actualizada");
    }

    @PostMapping("/password/pin")
    @Operation(summary = "Enviar PIN para cambiar mi contraseña", description = "Envía al correo de la identidad autenticada. Máximo 1 envío por minuto y 5 por hora.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Solicitud procesada; los reenvíos durante el límite se omiten")
    @ApiResponse(responseCode = "400", description = "La cuenta no tiene correo disponible")
    @ApiResponse(responseCode = "401", description = "Token inválido")
    @ApiResponse(responseCode = "500", description = "No fue posible procesar el envío")
    public MessageResponse changePin(@AuthenticationPrincipal Jwt jwt) {
        pins.requestChange(jwt.getSubject());
        return new MessageResponse("Si corresponde un nuevo envío, recibirás un PIN. Revisa tu bandeja y spam.");
    }

    @PostMapping("/password-reset/request")
    @Operation(summary = "Solicitar PIN de recuperación", description = "Ruta pública. Solo envía a una cuenta existente y habilitada. Respuesta genérica para cualquier correo. PIN de 6 dígitos, vigencia de 15 minutos, máximo 1 envío por minuto y 5 por hora.")
    @ApiResponse(responseCode = "200", description = "Solicitud procesada sin revelar si existe la cuenta")
    @ApiResponse(responseCode = "400", description = "Correo inválido")
    @ApiResponse(responseCode = "500", description = "No fue posible procesar el envío")
    public MessageResponse resetPin(@Valid @RequestBody ec.paktay.auth.dto.PasswordPinRequest request) {
        pins.requestReset(request.email());
        return new MessageResponse("Si el correo está registrado, recibirás un PIN. Revisa tu bandeja y spam.");
    }

    @PostMapping("/password-reset/complete")
    @Operation(summary = "Recuperar contraseña con PIN", description = "Paso 3, ruta pública. Consume resetToken obtenido al validar el PIN y actualiza la contraseña en Keycloak. Token de un solo uso, válido durante 10 minutos.")
    @ApiResponse(responseCode = "200", description = "Contraseña actualizada")
    @ApiResponse(responseCode = "400", description = "Validación inválida, expirada o contraseña inválida")
    @ApiResponse(responseCode = "502", description = "Keycloak no disponible")
    public MessageResponse resetComplete(@Valid @RequestBody ec.paktay.auth.dto.PasswordResetRequest request) {
        pins.completeReset(request.email(), request.resetToken(), request.newPassword());
        return new MessageResponse("Contraseña actualizada");
    }

    @PostMapping("/password-reset/verify")
    @Operation(summary = "Validar PIN de recuperación", description = "Paso 2, ruta pública. Valida el PIN sin cambiar la contraseña y entrega resetToken de un solo uso, válido por 10 minutos. Máximo 5 intentos de PIN.")
    @ApiResponse(responseCode = "200", description = "PIN validado; autorización temporal emitida")
    @ApiResponse(responseCode = "400", description = "Correo o PIN inválido, expirado o agotado")
    @ApiResponse(responseCode = "500", description = "No fue posible verificar la identidad")
    public ec.paktay.auth.dto.PasswordVerificationResponse verifyReset(@Valid @RequestBody ec.paktay.auth.dto.PasswordVerifyRequest request) {
        return new ec.paktay.auth.dto.PasswordVerificationResponse(pins.verifyReset(request.email(), request.pin()), 600);
    }

    @PostMapping("/password/verify")
    @Operation(summary = "Validar PIN de cambio de contraseña", description = "Paso 2. Valida el PIN de la cuenta autenticada y entrega resetToken de un solo uso, válido por 10 minutos. Máximo 5 intentos de PIN.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "PIN validado; autorización temporal emitida")
    @ApiResponse(responseCode = "400", description = "PIN inválido, expirado o agotado")
    @ApiResponse(responseCode = "401", description = "Token de sesión inválido")
    @ApiResponse(responseCode = "500", description = "No fue posible verificar la identidad")
    public ec.paktay.auth.dto.PasswordVerificationResponse verifyChange(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ec.paktay.auth.dto.PasswordChangeVerifyRequest request) {
        return new ec.paktay.auth.dto.PasswordVerificationResponse(pins.verifyChange(jwt.getSubject(), request.pin()), 600);
    }
}
