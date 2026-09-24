package ec.paktay.business.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ec.paktay.business.dto.MerchantRuleResponse;
import ec.paktay.business.exception.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reglas comercio → categoría (user_consumption_selections, normalization_version 2).
 *
 * Una regla nace o cambia en dos momentos: al guardar una captura de Wallet desde
 * "Por revisar" y al cambiar la categoría de un gasto de Wallet ya guardado. Gana
 * la última asignación. Mover una regla a otra categoría vale desde el próximo pago
 * de ese comercio: los gastos ya guardados no se recalculan.
 */
@Service
public class MerchantRuleService {
    static final String NOT_FOUND = "La regla no existe o no pertenece al usuario";

    private final JdbcClient jdbc;
    private final UserAccountService users;
    private final AuditService audit;

    public MerchantRuleService(JdbcClient jdbc, UserAccountService users, AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    /** Crea la regla del comercio o la apunta a la categoría nueva, y suma un uso. */
    void remember(UUID userId, String merchant, UUID categoryId) {
        String key = MerchantKey.ruleKey(merchant);
        if (key == null) return;
        jdbc.sql("""
                insert into user_consumption_selections (user_id, consumption_name, merchant_normalized,
                    normalization_version, category_id)
                values (:userId, :name, :key, :version, :categoryId)
                on conflict (user_id, merchant_normalized, normalization_version) do update set
                    consumption_name = excluded.consumption_name, category_id = excluded.category_id,
                    active = true, selection_count = user_consumption_selections.selection_count + 1,
                    last_selected_at = now(), updated_at = now()
                """).param("userId", userId).param("name", merchant.trim()).param("key", key)
                .param("version", MerchantKey.RULE_VERSION).param("categoryId", categoryId).update();
    }

    /** Reglas activas del usuario, de la más usada a la menos; opcionalmente de una categoría. */
    @Transactional
    public List<MerchantRuleResponse> list(UUID userId, UUID categoryId) {
        users.ensureActiveUser(userId);
        StringBuilder sql = new StringBuilder("""
                select s.id, s.merchant_normalized, s.consumption_name, s.category_id, uc.name as category_name,
                       s.selection_count, s.last_selected_at, s.updated_at
                  from user_consumption_selections s
                  join user_categories uc on uc.id = s.category_id
                 where s.user_id = :userId and s.active and s.normalization_version = :version and uc.active
                """);
        if (categoryId != null) sql.append(" and s.category_id = :categoryId");
        sql.append(" order by s.selection_count desc, s.last_selected_at desc");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("userId", userId)
                .param("version", MerchantKey.RULE_VERSION);
        if (categoryId != null) statement.param("categoryId", categoryId);
        return statement.query((rs, rowNum) -> new MerchantRuleResponse(rs.getObject("id", UUID.class),
                rs.getString("merchant_normalized"), rs.getString("consumption_name"),
                rs.getObject("category_id", UUID.class), rs.getString("category_name"),
                rs.getInt("selection_count"), rs.getObject("last_selected_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    /** Mueve la regla a otra categoría del usuario. No cambia gastos ya guardados. */
    @Transactional
    public MerchantRuleResponse move(UUID userId, UUID ruleId, UUID categoryId) {
        users.ensureActiveUser(userId);
        boolean category = jdbc.sql("select exists(select 1 from user_categories where id = :id and user_id = :userId and active)")
                .param("id", categoryId).param("userId", userId).query(Boolean.class).single();
        if (!category) throw new IllegalArgumentException("La categoría no existe, no pertenece al usuario o está inactiva");
        int updated = jdbc.sql("""
                update user_consumption_selections set category_id = :categoryId, updated_at = now()
                 where id = :id and user_id = :userId and active and normalization_version = :version
                """).param("categoryId", categoryId).param("id", ruleId).param("userId", userId)
                .param("version", MerchantKey.RULE_VERSION).update();
        if (updated == 0) throw new NotFoundException(NOT_FOUND);
        audit.record(userId, "UPDATE", "merchant_rule", ruleId, Map.of("categoryId", categoryId));
        return list(userId, null).stream().filter(rule -> rule.id().equals(ruleId)).findFirst()
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
    }
}
