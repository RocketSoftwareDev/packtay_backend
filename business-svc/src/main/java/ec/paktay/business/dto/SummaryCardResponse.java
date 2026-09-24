package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Tarjeta dentro del resumen del mes. */
public record SummaryCardResponse(
        UUID cardId,
        @Schema(description = "Apodo visual; puede ser null", nullable = true)
        String alias,
        @Schema(description = "Nombre de Wallet; null si no tiene o si la tarjeta fue eliminada", nullable = true)
        String walletName,
        String bankName,
        @Schema(nullable = true)
        String bankLogoUrl,
        @Schema(allowableValues = {"ACTIVE", "INACTIVE", "DELETED"})
        String status,
        @Schema(description = "Gastos ACTIVE de tipo EXPENSE del mes con esta tarjeta, en la moneda del presupuesto")
        BigDecimal spent,
        @Schema(description = "Límite propio de la tarjeta para el mes (budget_allocations, alcance CARD); sólo texto informativo", nullable = true)
        BigDecimal ownLimit) {
}
