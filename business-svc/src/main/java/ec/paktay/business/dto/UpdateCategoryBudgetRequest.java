package ec.paktay.business.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.Positive;

public record UpdateCategoryBudgetRequest(@Positive BigDecimal individualAmount, boolean active) { }
