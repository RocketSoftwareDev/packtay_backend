package ec.paktay.auth.dto;

public record OAuthConfigResponse(String issuer, String clientId, String flow, String pkce) {
}

