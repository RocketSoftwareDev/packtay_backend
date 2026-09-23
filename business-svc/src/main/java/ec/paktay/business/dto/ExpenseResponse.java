package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record ExpenseResponse(UUID id, UUID cardId, String cardName, UUID categoryId, String categoryName,
                              String origin, BigDecimal amount, String currencyCode, String merchantRaw,
                              OffsetDateTime occurredAt,
                              @Schema(description = "Estado de la tarjeta del gasto: ACTIVE, INACTIVE o DELETED. "
                                      + "Los gastos de una tarjeta eliminada siguen contando; la app la muestra como \"Eliminada\".",
                                      allowableValues = {"ACTIVE", "INACTIVE", "DELETED"})
                              String cardStatus,
                              @Schema(description = "EXPENSE (consumo) o REFUND (registro compensatorio, desde el día 4)",
                                      allowableValues = {"EXPENSE", "REFUND"})
                              String kind,
                              @Schema(description = "ACTIVE o VOIDED (anulado, desde el día 4). Los totales sólo cuentan ACTIVE + EXPENSE.",
                                      allowableValues = {"ACTIVE", "VOIDED"})
                              String status,
                              @Schema(description = "La tarjeta o categoría la asignó una regla del teléfono")
                              boolean assignedByRule) {
}
