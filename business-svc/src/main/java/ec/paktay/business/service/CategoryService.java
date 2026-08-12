package ec.paktay.business.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ec.paktay.business.dto.CategoryResponse;
import ec.paktay.business.dto.SystemCategoryResponse;
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
                select id, name, origin::text, active, created_at
                  from user_categories
                 where user_id = :userId
                 order by active desc, origin, name
                """).param("userId", userId).query(this::map).list();
    }

    @Transactional
    public CategoryResponse createUserCategory(UUID userId, String name) {
        users.ensureActiveUser(userId);
        String cleanName = name.trim();
        String normalized = normalize(cleanName);
        try {
            return jdbc.sql("""
                    insert into user_categories (user_id, origin, name, normalized_name)
                    values (:userId, 'CUSTOM', :name, :normalized)
                    returning id, name, origin::text, active, created_at
                    """).param("userId", userId).param("name", cleanName).param("normalized", normalized)
                    .query(this::map).single();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya existe una categoría con ese nombre");
        }
    }

    @Transactional
    public SystemCategoryResponse createSystemCategory(String name, short displayOrder) {
        String cleanName = name.trim();
        String normalized = normalize(cleanName);
        try {
            return jdbc.sql("""
                    insert into system_categories (name, normalized_name, display_order)
                    values (:name, :normalized, :displayOrder)
                    returning id, name, active, display_order, created_at
                    """).param("name", cleanName).param("normalized", normalized).param("displayOrder", displayOrder)
                    .query((rs, rowNum) -> new SystemCategoryResponse(rs.getObject("id", UUID.class), rs.getString("name"),
                            rs.getBoolean("active"), rs.getShort("display_order"), rs.getObject("created_at", OffsetDateTime.class))).single();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya existe una categoría predeterminada con ese nombre u orden");
        }
    }

    private String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").trim().toUpperCase(Locale.ROOT);
    }

    private CategoryResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new CategoryResponse(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("origin"),
                rs.getBoolean("active"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
