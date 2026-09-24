package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Único formato de gasto que expone la API. Lo construye sólo ExpenseQueryService.mapRow. */
public record ExpenseResponse(UUID id, UUID cardId,
                              @Schema(description = "Nombre de Wallet de la tarjeta; puede ser null")
                              String cardName,
                              @Schema(description = "Estado de la tarjeta del gasto: ACTIVE, INACTIVE o DELETED. "
                                      + "Los gastos de una tarjeta eliminada siguen contando; la app la muestra como \"Eliminada\".",
                                      allowableValues = {"ACTIVE", "INACTIVE", "DELETED"})
                              String cardStatus,
                              UUID categoryId, String categoryName,
                              @Schema(description = "MANUAL (registrado en la app) o AUTOMATIC (capturado por Wallet). "
                                      + "El monto y el comercio de un AUTOMATIC no se editan.",
                                      allowableValues = {"MANUAL", "AUTOMATIC"})
                              String origin,
                              @Schema(description = "EXPENSE (consumo) o REFUND (registro compensatorio creado al anular un gasto)",
                                      allowableValues = {"EXPENSE", "REFUND"})
                              String kind,
                              @Schema(description = "ACTIVE o VOIDED (anulado). Los totales sólo cuentan ACTIVE + EXPENSE.",
                                      allowableValues = {"ACTIVE", "VOIDED"})
                              String status,
                              @Schema(description = "En un gasto anulado, id del registro REFUND que lo compensa; null en el resto")
                              UUID voidedByExpenseId,
                              @Schema(description = "La tarjeta o categoría la asignó una regla del teléfono")
                              boolean assignedByRule,
                              @Schema(description = "Monto positivo en la moneda del gasto; también positivo en un REFUND")
                              BigDecimal amount,
                              String currencyCode, String merchantRaw,
                              @Schema(description = "Instante del consumo; un REFUND conserva el del gasto original")
                              OffsetDateTime occurredAt,
                              @Schema(description = "Última modificación de la fila; sirve para la sincronización incremental (since)")
                              OffsetDateTime updatedAt,
                              @Schema(description = "Monto de la compra en su moneda original (pago en otra moneda); null si fue en la moneda del gasto")
                              BigDecimal originalAmount,
                              @Schema(description = "Moneda de originalAmount; null si no aplica")
                              String originalCurrencyCode) {
}
