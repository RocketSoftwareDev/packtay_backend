package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CategoryBudgetResponse(UUID categoryId, String alias, String icon, String colorDark,
                                     String colorLight, BigDecimal individualAmount, boolean active) { }
