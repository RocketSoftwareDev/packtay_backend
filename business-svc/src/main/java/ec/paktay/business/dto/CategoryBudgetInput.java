package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CategoryBudgetInput(@NotNull UUID categoryId, @Positive BigDecimal individualAmount) { }
