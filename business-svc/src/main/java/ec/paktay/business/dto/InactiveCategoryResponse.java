package ec.paktay.business.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Una categoría desactivada y si se puede eliminar (día 9).
 *
 * - lastExpenseAt: último gasto ACTIVE (null si no tiene).
 * - expensesToMove: gastos que pasarían a «Sin categoría» al eliminarla.
 * - deletable: sin gastos en los últimos 3 meses y sin recurrentes que la usen.
 * - deletableFrom: si hoy no se puede por los 3 meses, desde qué día sí.
 * - blockingRecurring: nombres de los pagos recurrentes activos o pausados que la usan.
 */
public record InactiveCategoryResponse(UUID id, UUID systemCategoryId, String name, String icon, String colorDark,
                                       String colorLight, String origin, OffsetDateTime lastExpenseAt, int expensesToMove,
                                       boolean deletable, LocalDate deletableFrom, List<String> blockingRecurring) {
}
