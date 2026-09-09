package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record SaveBudgetRequest(
        @Positive BigDecimal globalAmount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
        @NotBlank @Pattern(regexp = "^(THIS_MONTH|MONTHLY)$") String recurrence,
        @Valid List<CategoryBudgetInput> categories) { }
