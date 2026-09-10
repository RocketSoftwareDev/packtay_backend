package ec.paktay.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
public record PasswordResetRequest(@NotBlank @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max=320) String email, @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String resetToken, @NotBlank @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,128}$", message = "Debe tener 10 caracteres, mayúscula, minúscula, número y símbolo") String newPassword) {}
