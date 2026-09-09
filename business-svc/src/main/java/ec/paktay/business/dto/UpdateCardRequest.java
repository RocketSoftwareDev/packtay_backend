package ec.paktay.business.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCardRequest(
        @Size(max = 80) String alias,
        @jakarta.validation.constraints.NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String colorDark,
        @jakarta.validation.constraints.NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String colorLight
) {}
