package ec.paktay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminLoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 256) String password) {
}
