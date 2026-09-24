package ec.paktay.business.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record VoidExpenseResponse(
        @Schema(description = "Gasto original, ya con status VOIDED y voidedByExpenseId apuntando al REFUND")
        ExpenseResponse voided,
        @Schema(description = "Registro compensatorio (kind REFUND, status ACTIVE) con el mismo monto, tarjeta, categoría y fecha")
        ExpenseResponse refund) {
}
