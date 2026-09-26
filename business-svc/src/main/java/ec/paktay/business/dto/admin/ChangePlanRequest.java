package ec.paktay.business.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ChangePlanRequest(@NotNull @Pattern(regexp = "FREE|PRO|DUO") String plan) {
}
