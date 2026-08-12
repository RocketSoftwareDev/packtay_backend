package ec.paktay.business.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateSystemCategoryRequest(@NotBlank @Size(max = 80) String name, @Positive short displayOrder) {
}
