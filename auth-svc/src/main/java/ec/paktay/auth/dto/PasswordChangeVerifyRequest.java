package ec.paktay.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
public record PasswordChangeVerifyRequest(@NotBlank @Pattern(regexp="[0-9]{6}") String pin) {}
