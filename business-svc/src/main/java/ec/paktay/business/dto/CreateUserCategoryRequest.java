package ec.paktay.business.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreateUserCategoryRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9-]+$") @Size(max = 50) String code,
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Pattern(regexp = "^[a-z0-9-]+$") @Size(max = 60) String icon,
        @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String colorDark,
        @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String colorLight,
        @Positive short sortOrder) {
}
