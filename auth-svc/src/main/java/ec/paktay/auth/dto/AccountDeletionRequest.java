package ec.paktay.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountDeletionRequest(@NotBlank String password) {
}
