package ec.paktay.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
public record PasswordPinRequest(@NotBlank @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max=320) String email) {}
