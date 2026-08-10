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
public class AuthController {
    private final KeycloakIdentityService identities;
    private final KeycloakProperties properties;

    public AuthController(KeycloakIdentityService identities, KeycloakProperties properties) {
        this.identities = identities;
        this.properties = properties;
    }

    @GetMapping("/oauth-config")
    public OAuthConfigResponse oauthConfig() {
        return new OAuthConfigResponse(properties.publicUrl() + "/realms/" + properties.realm(), properties.mobileClientId(), "authorization_code", "S256");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return identities.register(request);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return identities.login(request);
    }

    @PutMapping("/password")
    public MessageResponse changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordChangeRequest request) {
        String username = jwt.getClaimAsString("preferred_username");
        identities.verifyCredentials(username, request.currentPassword());
        identities.replacePassword(jwt.getSubject(), request.newPassword(), false);
        return new MessageResponse("Contraseña actualizada");
    }
}
