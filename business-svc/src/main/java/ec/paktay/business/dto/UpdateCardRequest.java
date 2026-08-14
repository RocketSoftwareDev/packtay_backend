package ec.paktay.business.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCardRequest(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String colorDark,
        @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String colorLight
) {}
