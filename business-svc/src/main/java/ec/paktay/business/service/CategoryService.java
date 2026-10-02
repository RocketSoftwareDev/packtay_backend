package ec.paktay.business.service;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ec.paktay.business.dto.CategoryResponse;
import ec.paktay.business.dto.InactiveCategoryResponse;
import ec.paktay.business.dto.SystemCategoryResponse;
import ec.paktay.business.dto.CreateSystemCategoryRequest;
import ec.paktay.business.dto.CreateUserCategoryRequest;
import ec.paktay.business.dto.UpdateCategoryRequest;
import ec.paktay.business.dto.UpdateCategoryAppearanceRequest;
import ec.paktay.business.exception.ConflictException;
import ec.paktay.business.exception.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Categorías del usuario (día 9, bug de testers):
 *
 * - El catálogo del admin (system_categories) solo sirve para copiar: «Agregar» crea la copia
 *   personal y editable del usuario (o reactiva la que tenía).
 * - Una categoría del usuario está activa o desactivada. Desactivar la oculta, conserva sus
 *   gastos y la saca del presupuesto; se puede reactivar.
 * - Eliminar es definitivo y solo para desactivadas sin gastos en los últimos 3 meses ni
 *   recurrentes que la usen ({@link CategoryDeletionRule}); sus gastos viejos pasan a la
 *   reservada «Sin categoría».
 * - La reservada (reserved = true, V14) no se edita, no se desactiva ni se elimina, y no se
 *   presupuesta ni se asigna a gastos nuevos. Solo se lista si tiene gastos.
 */
@Service
public class CategoryService {
    private static final String COLUMNS = """
            id, system_category_id, code, alias as display_name, name as base_name, icon, color_dark, color_light, sort_order,
            origin::text, active, created_at, reserved
            """;
    private static final String NOT_OWNED = "La categoría no existe o no pertenece al usuario";

    private final JdbcClient jdbc;
    private final UserAccountService users;

    public CategoryService(JdbcClient jdbc, UserAccountService users) { this.jdbc = jdbc; this.users = users; }

    /** Activas, más «Sin categoría» solo si tiene gastos (así no aparece en apps viejas sin motivo). */
    @Transactional
    public List<CategoryResponse> listUserCategories(UUID userId) {
        users.ensureActiveUser(userId);
        return jdbc.sql("select " + COLUMNS + """
                  from user_categories uc
                 where uc.user_id = :userId and uc.active
                   and (not uc.reserved or exists (select 1 from expenses e where e.user_id = uc.user_id and e.category_id = uc.id))
                 order by uc.reserved, uc.sort_order, uc.name
                """).param("userId", userId).query(this::map).list();
    }

    /** Desactivadas, con lo que hace falta para la papelera y la hoja «no se puede eliminar». */
    @Transactional
    public List<InactiveCategoryResponse> listInactiveCategories(UUID userId) {
        users.ensureActiveUser(userId);
        ZoneId zone = users.zoneOf(userId);
        LocalDate today = LocalDate.now(zone);
        return jdbc.sql("""
                select uc.id, uc.system_category_id, uc.alias, uc.icon, uc.color_dark, uc.color_light, uc.origin::text,
                       (select max(e.occurred_at) from expenses e
                         where e.user_id = uc.user_id and e.category_id = uc.id and e.status = 'ACTIVE') as last_active,
                       (select count(*) from expenses e where e.user_id = uc.user_id and e.category_id = uc.id) as total_expenses,
                       (select coalesce(array_agg(r.name order by r.name), '{}') from recurring_payments r
                         where r.user_id = uc.user_id and r.category_id = uc.id and r.status in ('ACTIVE', 'PAUSED')) as recurring
                  from user_categories uc
                 where uc.user_id = :userId and not uc.active and not uc.reserved
                 order by uc.alias
                """).param("userId", userId).query((rs, n) -> {
                    OffsetDateTime last = rs.getObject("last_active", OffsetDateTime.class);
                    List<String> recurring = strings(rs.getArray("recurring"));
                    CategoryDeletionRule.Verdict verdict = CategoryDeletionRule.evaluate(
                            last == null ? null : last.atZoneSameInstant(zone).toLocalDate(), recurring, today);
                    return new InactiveCategoryResponse(rs.getObject("id", UUID.class),
                            rs.getObject("system_category_id", UUID.class), rs.getString("alias"), rs.getString("icon"),
                            rs.getString("color_dark"), rs.getString("color_light"), rs.getString("origin"), last,
                            rs.getInt("total_expenses"), verdict.deletable(), verdict.deletableFrom(), verdict.blockingRecurring());
                }).list();
    }

    @Transactional
    public CategoryResponse createUserCategory(UUID userId, CreateUserCategoryRequest request) {
        users.ensureActiveUser(userId);
        String cleanName = request.name().trim();
        String normalized = normalize(cleanName);
        ensureNameFree(userId, normalized, null);
        try {
            return jdbc.sql("""
                    insert into user_categories
                        (user_id, origin, code, name, alias, normalized_name, icon, color_dark, color_light, sort_order)
                    values (:userId, 'CUSTOM', :code, :name, :name, :normalized, :icon, :colorDark, :colorLight, :sortOrder)
                    returning\s""" + COLUMNS).param("userId", userId).param("name", cleanName).param("normalized", normalized)
                    .param("code", request.code()).param("icon", request.icon())
                    .param("colorDark", request.colorDark()).param("colorLight", request.colorLight())
                    .param("sortOrder", request.sortOrder())
                    .query(this::map).single();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya tienes una categoría con ese nombre");
        }
    }

    @Transactional
    public CategoryResponse updateUserCategory(UUID userId, UUID categoryId, UpdateCategoryRequest request) {
        users.ensureActiveUser(userId);
        ensureNameFree(userId, normalize(request.name()), categoryId);
        if (!request.active()) leaveBudgets(userId, categoryId);
        try {
            return jdbc.sql("""
                    update user_categories
                       set code = :code, alias = :name, normalized_name = :normalized, icon = :icon,
                           color_dark = :colorDark, color_light = :colorLight, sort_order = :sortOrder,
                           active = :active
                     where id = :id and user_id = :userId and not reserved
                    returning\s""" + COLUMNS).param("code", request.code()).param("name", request.name().trim())
                    .param("normalized", normalize(request.name())).param("icon", request.icon())
                    .param("colorDark", request.colorDark()).param("colorLight", request.colorLight())
                    .param("sortOrder", request.sortOrder()).param("active", request.active())
                    .param("id", categoryId).param("userId", userId).query(this::map).optional()
                    .orElseThrow(() -> new IllegalArgumentException(NOT_OWNED));
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya existe una categoría con ese código, nombre u orden");
        }
    }

    @Transactional
    public CategoryResponse updateAppearance(UUID userId, UUID categoryId, UpdateCategoryAppearanceRequest request) {
        users.ensureActiveUser(userId);
        ensureNameFree(userId, normalize(request.alias()), categoryId);
        try {
            CategoryResponse updated = jdbc.sql("""
                    update user_categories
                       set alias = :alias, normalized_name = :normalized, icon = :icon,
                           color_dark = :colorDark, color_light = :colorLight, active = :active,
                           updated_at = now()
                     where id = :id and user_id = :userId and not reserved
                    returning\s""" + COLUMNS).param("alias", request.alias().trim()).param("normalized", normalize(request.alias()))
                    .param("icon", request.icon()).param("colorDark", request.colorDark())
                    .param("colorLight", request.colorLight()).param("active", request.active())
                    .param("id", categoryId).param("userId", userId).query(this::map).optional()
                    .orElseThrow(() -> new IllegalArgumentException(NOT_OWNED));
            if (!updated.active()) leaveBudgets(userId, categoryId);
            return updated;
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya existe una categoría con ese alias");
        }
    }

    /**
     * Desactivar (DELETE /{id}): siempre conserva la categoría y sus gastos, y la saca del
     * presupuesto. Antes del día 9 borraba en el acto las que no tenían gastos y no había forma
     * de recuperar ninguna.
     */
    @Transactional
    public void deactivateUserCategory(UUID userId, UUID categoryId) {
        users.ensureActiveUser(userId);
        int updated = jdbc.sql("update user_categories set active = false where id = :id and user_id = :userId and active and not reserved")
                .param("id", categoryId).param("userId", userId).update();
        if (updated == 0) throw new IllegalArgumentException("La categoría no existe, no pertenece al usuario o ya está inactiva");
        leaveBudgets(userId, categoryId);
    }

    @Transactional
    public CategoryResponse reactivateUserCategory(UUID userId, UUID categoryId) {
        users.ensureActiveUser(userId);
        return jdbc.sql("update user_categories set active = true where id = :id and user_id = :userId and not reserved returning " + COLUMNS)
                .param("id", categoryId).param("userId", userId).query(this::map).optional()
                .orElseThrow(() -> new NotFoundException(NOT_OWNED));
    }

    /**
     * Eliminar para siempre (DELETE /{id}/permanent). Solo desactivadas que cumplen
     * {@link CategoryDeletionRule}. Sus gastos (de cualquier fecha y estado) pasan a «Sin
     * categoría»; los recurrentes cancelados también. Se borran sus reglas de comercios y sus
     * presupuestos. Devuelve cuántos gastos se movieron.
     */
    @Transactional
    public int deleteUserCategory(UUID userId, UUID categoryId) {
        users.ensureActiveUser(userId);
        InactiveCategoryResponse inactive = listInactiveCategories(userId).stream()
                .filter(c -> c.id().equals(categoryId)).findFirst()
                .orElseThrow(() -> {
                    boolean active = jdbc.sql("select exists(select 1 from user_categories where id = :id and user_id = :userId and active and not reserved)")
                            .param("id", categoryId).param("userId", userId).query(Boolean.class).single();
                    return active ? new ConflictException("Desactiva la categoría antes de eliminarla")
                            : new NotFoundException(NOT_OWNED);
                });
        if (!inactive.blockingRecurring().isEmpty()) {
            throw new ConflictException("La usa el pago recurrente " + String.join(", ", inactive.blockingRecurring())
                    + ". Cámbiale la categoría primero.");
        }
        if (!inactive.deletable()) {
            throw new ConflictException("Tiene gastos en los últimos 3 meses. Podrás eliminarla desde el "
                    + inactive.deletableFrom() + ".");
        }
        UUID reserved = jdbc.sql("select public.ensure_reserved_category(:userId)").param("userId", userId)
                .query(UUID.class).single();
        // Mover gastos de meses cerrados: salida controlada de protect_expense_update (V14).
        jdbc.sql("select set_config('paktay.category_move', :userId, true)").param("userId", userId.toString())
                .query(String.class).single();
        int moved = jdbc.sql("update expenses set category_id = :reserved where user_id = :userId and category_id = :id")
                .param("reserved", reserved).param("userId", userId).param("id", categoryId).update();
        jdbc.sql("select set_config('paktay.category_move', '', true)").query(String.class).single();
        jdbc.sql("update recurring_payments set category_id = :reserved where user_id = :userId and category_id = :id")
                .param("reserved", reserved).param("userId", userId).param("id", categoryId).update();
        jdbc.sql("update pending_movements set suggested_category_id = null where user_id = :userId and suggested_category_id = :id")
                .param("userId", userId).param("id", categoryId).update();
        for (String table : List.of("user_consumption_selections", "user_category_budgets", "budget_allocations")) {
            jdbc.sql("delete from " + table + " where user_id = :userId and category_id = :id")
                    .param("userId", userId).param("id", categoryId).update();
        }
        jdbc.sql("delete from user_categories where id = :id and user_id = :userId and not active and not reserved")
                .param("id", categoryId).param("userId", userId).update();
        return moved;
    }

    /** Crea o reactiva la copia personal de una categoría del catálogo. */
    @Transactional
    public CategoryResponse addSystemCategory(UUID userId, UUID systemCategoryId) {
        users.ensureActiveUser(userId);
        try {
            return jdbc.sql("""
                    insert into user_categories
                        (user_id, system_category_id, origin, code, name, alias, normalized_name,
                         icon, color_dark, color_light, sort_order)
                    select :userId, sc.id, 'SYSTEM', sc.code, sc.name, sc.name, sc.normalized_name,
                           sc.icon, sc.color_dark, sc.color_light, sc.display_order
                      from system_categories sc
                     where sc.id = :systemCategoryId and sc.active
                    on conflict (user_id, system_category_id) where system_category_id is not null
                    do update set active = true
                    returning\s""" + COLUMNS).param("userId", userId).param("systemCategoryId", systemCategoryId)
                    .query(this::map).optional()
                    .orElseThrow(() -> new IllegalArgumentException("La categoría predeterminada no existe o está inactiva"));
        } catch (DataIntegrityViolationException ex) {
            // Una propia con el mismo nombre o código.
            throw new IllegalArgumentException("Ya tienes una categoría con ese nombre");
        }
    }

    @Transactional
    public List<SystemCategoryResponse> listSystemCategories() {
        return jdbc.sql("""
                select id, code, name, parent_code, parent_name, icon, color_dark, color_light, display_order, active, created_at
                  from system_categories order by display_order, name
                """).query(this::mapSystem).list();
    }

    @Transactional
    public SystemCategoryResponse createSystemCategory(CreateSystemCategoryRequest request) {
        String cleanName = request.name().trim();
        String normalized = normalize(cleanName);
        try {
            SystemCategoryResponse created = jdbc.sql("""
                    insert into system_categories
                        (code, name, normalized_name, parent_code, parent_name, icon, color_dark, color_light, display_order)
                    values (:code, :name, :normalized, :parentCode, :parentName, :icon, :colorDark, :colorLight, :sortOrder)
                    returning id, code, name, parent_code, parent_name, icon, color_dark, color_light, display_order, active, created_at
                    """).param("code", request.code()).param("name", cleanName).param("normalized", normalized)
                    .param("parentCode", request.parentCode()).param("parentName", request.parentName().trim())
                    .param("icon", request.icon()).param("colorDark", request.colorDark())
                    .param("colorLight", request.colorLight()).param("sortOrder", request.sortOrder())
                    .query(this::mapSystem).single();
            return created;
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya existe una categoría predeterminada con ese nombre u orden");
        }
    }

    /** Desde que se desactiva, la categoría sale de los presupuestos (y no se copia al mes siguiente). */
    private void leaveBudgets(UUID userId, UUID categoryId) {
        jdbc.sql("update user_category_budgets set active = false, updated_at = now() where user_id = :userId and category_id = :id and active")
                .param("userId", userId).param("id", categoryId).update();
    }

    /**
     * Un nombre choca solo con las categorías propias del usuario (no con el catálogo). Si choca
     * con una desactivada, se dice cómo recuperarla en vez de un «ya existe» sin salida.
     */
    private void ensureNameFree(UUID userId, String normalized, UUID except) {
        if ("SIN CATEGORIA".equals(normalized)) {
            throw new IllegalArgumentException("«Sin categoría» está reservada. Elige otro nombre.");
        }
        List<Object[]> clash = jdbc.sql("""
                select alias, active from user_categories
                 where user_id = :userId and normalized_name = :normalized and not reserved
                   and (cast(:except as uuid) is null or id <> cast(:except as uuid))
                """).param("userId", userId).param("normalized", normalized).param("except", except, java.sql.Types.OTHER)
                .query((rs, n) -> new Object[] {rs.getString("alias"), rs.getBoolean("active")}).list();
        if (clash.isEmpty()) return;
        if (Boolean.TRUE.equals(clash.get(0)[1])) throw new IllegalArgumentException("Ya tienes una categoría con ese nombre");
        throw new ConflictException("Ya tienes «" + clash.get(0)[0] + "» desactivada. Reactívala desde Categorías.");
    }

    private String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(String::valueOf).toList();
    }

    private CategoryResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new CategoryResponse(rs.getObject("id", UUID.class), rs.getObject("system_category_id", UUID.class),
                rs.getString("code"), rs.getString("display_name"), rs.getString("base_name"),
                rs.getString("icon"), rs.getString("color_dark"), rs.getString("color_light"),
                rs.getShort("sort_order"), rs.getString("origin"), rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getBoolean("reserved"));
    }

    private SystemCategoryResponse mapSystem(ResultSet rs, int rowNum) throws SQLException {
        return new SystemCategoryResponse(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("parent_code"), rs.getString("parent_name"), rs.getString("icon"), rs.getString("color_dark"), rs.getString("color_light"),
                rs.getShort("display_order"), rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
