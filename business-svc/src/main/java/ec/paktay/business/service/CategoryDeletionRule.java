package ec.paktay.business.service;

import java.time.LocalDate;
import java.util.List;

/**
 * Cuándo se puede eliminar una categoría desactivada (día 9):
 *
 * - sin gastos ACTIVE en los últimos 3 meses (por la fecha del gasto, en el calendario del
 *   usuario): desde el día en que se cumplen 3 meses del último, se puede;
 * - sin pagos recurrentes activos o pausados que la usen (se le cambia la categoría antes;
 *   borrar la categoría borraría el recurrente en cascada).
 *
 * Los gastos más viejos no impiden nada: pasan a «Sin categoría».
 */
final class CategoryDeletionRule {
    static final int QUIET_MONTHS = 3;

    private CategoryDeletionRule() { }

    record Verdict(boolean deletable, LocalDate deletableFrom, List<String> blockingRecurring) { }

    /**
     * @param lastActiveExpense fecha (en la zona del usuario) del último gasto ACTIVE, o null.
     * @param today             hoy en la zona del usuario.
     */
    static Verdict evaluate(LocalDate lastActiveExpense, List<String> blockingRecurring, LocalDate today) {
        LocalDate from = lastActiveExpense == null ? null : lastActiveExpense.plusMonths(QUIET_MONTHS);
        boolean quiet = from == null || !today.isBefore(from);
        List<String> recurring = blockingRecurring == null ? List.of() : List.copyOf(blockingRecurring);
        return new Verdict(quiet && recurring.isEmpty(), quiet ? null : from, recurring);
    }
}
