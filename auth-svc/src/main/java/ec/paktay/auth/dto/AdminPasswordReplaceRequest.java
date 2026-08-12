package ec.paktay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AdminPasswordReplaceRequest(
        @NotBlank @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,128}$",
                message = "Debe tener 10 caracteres, mayúscula, minúscula, número y símbolo") String newPassword,
        boolean temporary) {
}

