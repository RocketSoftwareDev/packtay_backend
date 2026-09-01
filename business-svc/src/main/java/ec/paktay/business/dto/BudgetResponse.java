package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BudgetResponse(LocalDate periodMonth, BigDecimal globalAmount, String currencyCode, String recurrence,
                             BigDecimal spentAmount, List<CategoryBudgetResponse> categories) { }
