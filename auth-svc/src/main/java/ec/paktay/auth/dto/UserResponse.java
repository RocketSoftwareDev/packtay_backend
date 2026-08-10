package ec.paktay.auth.dto;

public record UserResponse(String id, String email, String displayName, boolean enabled) {
}

