package ec.paktay.business.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ec.paktay.business.dto.CategoryResponse;
import ec.paktay.business.dto.SystemCategoryResponse;
import ec.paktay.business.dto.CreateSystemCategoryRequest;
import ec.paktay.business.dto.CreateUserCategoryRequest;
import ec.paktay.business.dto.UpdateCategoryRequest;
import ec.paktay.business.dto.UpdateCategoryAppearanceRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {
    private final JdbcClient jdbc;
    private final UserAccountService users;

    public CategoryService(JdbcClient jdbc, UserAccountService users) { this.jdbc = jdbc; this.users = users; }

    @Transactional
    public List<CategoryResponse> listUserCategories(UUID userId) {
        users.ensureActiveUser(userId);
        return jdbc.sql("""
                select id, system_category_id, code, alias as display_name, name as base_name, icon, color_dark, color_light, sort_order,
                       origin::text, active, created_at
                  from user_categories
                 where user_id = :userId and active
                 order by sort_order, name
                """).param("userId", userId).query(this::map).list();
    }

    @Transactional
    public CategoryResponse createUserCategory(UUID userId, CreateUserCategoryRequest request) {
        users.ensureActiveUser(userId);
        String cleanName = request.name().trim();
        String normalized = normalize(cleanName);
        try {
            return jdbc.sql("""
                    insert into user_categories
                        (user_id, origin, code, name, alias, normalized_name, icon, color_dark, color_light, sort_order)
                    values (:userId, 'CUSTOM', :code, :name, :name, :normalized, :icon, :colorDark, :colorLight, :sortOrder)
                    returning id, system_category_id, code, alias as display_name, name as base_name, icon, color_dark, color_light, sort_order,
                              origin::text, active, created_at
                    """).param("userId", userId).param("name", cleanName).param("normalized", normalized)
                    .param("code", request.code()).param("icon", request.icon())
                    .param("colorDark", request.colorDark()).param("colorLight", request.colorLight())
                    .param("sortOrder", request.sortOrder())
                    .query(this::map).single();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya existe una categoría con ese nombre");
        }
    }

    @Transactional
    public CategoryResponse updateUserCategory(UUID userId, UUID categoryId, UpdateCategoryRequest request) {
        users.ensureActiveUser(userId);
        try {
            return jdbc.sql("""
                    update user_categories
                       set code = :code, alias = :name, normalized_name = :normalized, icon = :icon,
                           color_dark = :colorDark, color_light = :colorLight, sort_order = :sortOrder,
                           active = :active
                     where id = :id and user_id = :userId
                    returning id, system_category_id, code, alias as display_name, name as base_name, icon, color_dark, color_light, sort_order,
                              origin::text, active, created_at
                    """).param("code", request.code()).param("name", request.name().trim())
                    .param("normalized", normalize(request.name())).param("icon", request.icon())
                    .param("colorDark", request.colorDark()).param("colorLight", request.colorLight())
                    .param("sortOrder", request.sortOrder()).param("active", request.active())
                    .param("id", categoryId).param("userId", userId).query(this::map).optional()
                    .orElseThrow(() -> new IllegalArgumentException("La categoría no existe o no pertenece al usuario"));
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya existe una categoría con ese código, nombre u orden");
        }
    }

    @Transactional
    public CategoryResponse updateAppearance(UUID userId, UUID categoryId, UpdateCategoryAppearanceRequest request) {
        users.ensureActiveUser(userId);
        try {
            return jdbc.sql("""
                    update user_categories
                       set alias = :alias, normalized_name = :normalized, icon = :icon,
                           color_dark = :colorDark, color_light = :colorLight, active = :active,
                           updated_at = now()
                     where id = :id and user_id = :userId
                    returning id, system_category_id, code, alias as display_name, name as base_name,
                              icon, color_dark, color_light, sort_order, origin::text, active, created_at
                    """).param("alias", request.alias().trim()).param("normalized", normalize(request.alias()))
                    .param("icon", request.icon()).param("colorDark", request.colorDark())
                    .param("colorLight", request.colorLight()).param("active", request.active())
                    .param("id", categoryId).param("userId", userId).query(this::map).optional()
                    .orElseThrow(() -> new IllegalArgumentException("La categoría no existe o no pertenece al usuario"));
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya existe una categoría con ese alias");
        }
    }

    @Transactional
    public void deactivateUserCategory(UUID userId, UUID categoryId) {
        users.ensureActiveUser(userId);
        int updated = jdbc.sql("update user_categories set active = false where id = :id and user_id = :userId and active")
                .param("id", categoryId).param("userId", userId).update();
        if (updated == 0) throw new IllegalArgumentException("La categoría no existe, no pertenece al usuario o ya está inactiva");
    }

    @Transactional
    public CategoryResponse addSystemCategory(UUID userId, UUID systemCategoryId) {
        users.ensureActiveUser(userId);
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
                returning id, system_category_id, code, alias as display_name, name as base_name, icon, color_dark, color_light,
                          sort_order, origin::text, active, created_at
                """).param("userId", userId).param("systemCategoryId", systemCategoryId)
                .query(this::map).optional()
                .orElseThrow(() -> new IllegalArgumentException("La categoría predeterminada no existe o está inactiva"));
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
            jdbc.sql("""
                    insert into user_categories
                        (user_id, system_category_id, origin, code, name, alias, normalized_name,
                         icon, color_dark, color_light, sort_order)
                    select u.id, sc.id, 'SYSTEM', sc.code, sc.name, sc.name, sc.normalized_name,
                           sc.icon, sc.color_dark, sc.color_light, sc.display_order
                      from app_users u cross join system_categories sc
                     where sc.id = :systemCategoryId and u.status = 'ACTIVE'
                    on conflict do nothing
                    """).param("systemCategoryId", created.id()).update();
            return created;
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya existe una categoría predeterminada con ese nombre u orden");
        }
    }

    @Transactional
    public SystemCategoryResponse updateSystemCategory(UUID categoryId, UpdateCategoryRequest request) {
        try {
            return jdbc.sql("""
                    update system_categories
                       set code = :code, name = :name, normalized_name = :normalized, icon = :icon,
                           color_dark = :colorDark, color_light = :colorLight,
                           display_order = :sortOrder, active = :active
                     where id = :id
                    returning id, code, name, parent_code, parent_name, icon, color_dark, color_light, display_order, active, created_at
                    """).param("code", request.code()).param("name", request.name().trim())
                    .param("normalized", normalize(request.name())).param("icon", request.icon())
                    .param("colorDark", request.colorDark()).param("colorLight", request.colorLight())
                    .param("sortOrder", request.sortOrder()).param("active", request.active()).param("id", categoryId)
                    .query(this::mapSystem).optional()
                    .orElseThrow(() -> new IllegalArgumentException("La categoría predeterminada no existe"));
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya existe una categoría predeterminada con ese código, nombre u orden");
        }
    }

    private String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").trim().toUpperCase(Locale.ROOT);
    }

    private CategoryResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new CategoryResponse(rs.getObject("id", UUID.class), rs.getObject("system_category_id", UUID.class),
                rs.getString("code"), rs.getString("display_name"), rs.getString("base_name"),
                rs.getString("icon"), rs.getString("color_dark"), rs.getString("color_light"),
                rs.getShort("sort_order"), rs.getString("origin"), rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private SystemCategoryResponse mapSystem(ResultSet rs, int rowNum) throws SQLException {
        return new SystemCategoryResponse(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("parent_code"), rs.getString("parent_name"), rs.getString("icon"), rs.getString("color_dark"), rs.getString("color_light"),
                rs.getShort("display_order"), rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
