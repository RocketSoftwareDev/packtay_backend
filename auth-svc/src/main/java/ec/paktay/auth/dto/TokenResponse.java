package ec.paktay.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tokens de Keycloak. password_change_required no viene de Keycloak: lo agrega el login del móvil
 * cuando la cuenta entró con una contraseña temporal del administrador (y solo entonces aparece).
 */
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") Integer expiresIn,
        @JsonProperty("refresh_expires_in") Integer refreshExpiresIn,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("password_change_required") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean passwordChangeRequired) {

    public TokenResponse withPasswordChangeRequired() {
        return new TokenResponse(accessToken, refreshToken, expiresIn, refreshExpiresIn, tokenType, true);
    }
}
