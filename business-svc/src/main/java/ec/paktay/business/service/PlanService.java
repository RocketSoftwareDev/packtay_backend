package ec.paktay.business.service;

import java.util.UUID;

import ec.paktay.business.dto.EntitlementsResponse;
import ec.paktay.business.exception.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Límites del plan Gratis (día 7). Pro y Duo no tienen límites.
 *
 * Sin fila en user_subscription el usuario es tester con Pro, como toda la beta: los
 * límites existen y se prueban poniendo plan = FREE, pero hoy nadie los ve.
 *
 * Las capturas del mes son los gastos AUTOMATIC (Wallet) del mes calendario del
 * usuario, anulados incluidos: anular no devuelve una captura, igual que no la
 * devuelve borrarla en el teléfono.
 */
@Service
public class PlanService {
    public static final int FREE_CARDS = CardService.FREE_PLAN_CARDS;
    public static final int FREE_CAPTURES_PER_MONTH = 20;
    public static final int FREE_BUDGET_CATEGORIES = 3;
    public static final int FREE_HISTORY_MONTHS = 3;

    private final JdbcClient jdbc;
    private final UserAccountService users;

    public PlanService(JdbcClient jdbc, UserAccountService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public String planOf(UUID userId) {
        return jdbc.sql("select plan from user_subscription where user_id = :userId and status = 'ACTIVE'")
                .param("userId", userId).query(String.class).optional().orElse("PRO");
    }

    public boolean isFree(UUID userId) {
        return "FREE".equals(planOf(userId));
    }

    public int capturesThisMonth(UUID userId) {
        return jdbc.sql("""
                select count(*) from expenses e join app_users u on u.id = e.user_id
                 where e.user_id = :userId and e.origin = 'AUTOMATIC' and e.kind = 'EXPENSE'
                   and e.occurred_at >= (date_trunc('month', now() at time zone u.timezone) at time zone u.timezone)
                """).param("userId", userId).query(Integer.class).single();
    }

    public int registeredCards(UUID userId) {
        return jdbc.sql("select count(*) from cards where user_id = :userId and status::text <> 'DELETED'")
                .param("userId", userId).query(Integer.class).single();
    }

    /** Categorías del mes actual con presupuesto activo (propio o global). */
    public int budgetCategories(UUID userId, UUID periodId) {
        return jdbc.sql("""
                select count(*) from user_category_budgets b join user_categories uc on uc.id = b.category_id
                 where b.user_id = :userId and b.period_id = :periodId and b.active and uc.active
                """).param("userId", userId).param("periodId", periodId).query(Integer.class).single();
    }

    /** 409 si un usuario Gratis ya tiene sus capturas del mes. */
    public void ensureCaptureRoom(UUID userId) {
        if (!isFree(userId)) return;
        if (capturesThisMonth(userId) >= FREE_CAPTURES_PER_MONTH) {
            throw new ConflictException("Llegaste a " + FREE_CAPTURES_PER_MONTH
                    + " capturas este mes en el plan Gratis. Puedes anotarlo a mano.");
        }
    }

    /** 409 si un usuario Gratis quiere más categorías con presupuesto de las permitidas. */
    public void ensureBudgetCategoriesRoom(UUID userId, int wanted) {
        if (!isFree(userId)) return;
        if (wanted > FREE_BUDGET_CATEGORIES) {
            throw new ConflictException("El plan Gratis permite presupuesto en " + FREE_BUDGET_CATEGORIES
                    + " categorías. Quita una o pasa al plan Pro.");
        }
    }

    @Transactional
    public EntitlementsResponse entitlements(UUID userId, java.util.function.Supplier<UUID> currentPeriod) {
        users.ensureActiveUser(userId);
        String plan = planOf(userId);
        boolean free = "FREE".equals(plan);
        return new EntitlementsResponse(plan,
                new EntitlementsResponse.Limits(free ? FREE_CARDS : null, free ? FREE_CAPTURES_PER_MONTH : null,
                        free ? FREE_BUDGET_CATEGORIES : null, free ? FREE_HISTORY_MONTHS : null),
                new EntitlementsResponse.Usage(registeredCards(userId), capturesThisMonth(userId),
                        budgetCategories(userId, currentPeriod.get())));
    }
}
