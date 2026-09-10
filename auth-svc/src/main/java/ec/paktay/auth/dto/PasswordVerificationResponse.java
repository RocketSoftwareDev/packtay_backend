package ec.paktay.auth.dto;
public record PasswordVerificationResponse(String resetToken, int expiresIn) {}
