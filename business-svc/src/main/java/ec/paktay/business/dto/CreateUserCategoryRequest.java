package ec.paktay.business.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserCategoryRequest(@NotBlank @Size(max = 80) String name) {
}
