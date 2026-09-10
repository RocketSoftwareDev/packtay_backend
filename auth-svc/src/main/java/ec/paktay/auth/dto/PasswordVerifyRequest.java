package ec.paktay.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
public record PasswordVerifyRequest(@NotBlank @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max=320) String email, @NotBlank @Pattern(regexp="[0-9]{6}") String pin) {}
