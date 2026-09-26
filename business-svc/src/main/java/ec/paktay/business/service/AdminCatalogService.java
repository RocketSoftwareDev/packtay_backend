package ec.paktay.business.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import ec.paktay.business.dto.admin.AdminActor;
import ec.paktay.business.dto.admin.AdminCatalog;
import ec.paktay.business.exception.ConflictException;
import ec.paktay.business.exception.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogos globales vistos desde el panel. Todos los cambios quedan en admin_audit.
 *
 * Reglas de monedas y países (las mismas que avisa la web):
 * - la moneda base no se desactiva;
 * - no se desactiva una moneda que usa un país activo;
 * - no se activa (ni se crea activo) un país cuya moneda está inactiva.
 */
@Service
public class AdminCatalogService {
    private final JdbcClient jdbc;
    private final AdminAuditService audit;

    public AdminCatalogService(JdbcClient jdbc, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- categorías

    public List<AdminCatalog.CategoryGroup> categories() {
        List<Object[]> rows = jdbc.sql("""
                select sc.id, sc.code, sc.name, sc.parent_code, sc.parent_name, sc.icon, sc.color_light,
                       sc.display_order, sc.active,
                       (select count(distinct uc.user_id) from user_categories uc
                         where uc.system_category_id = sc.id and uc.active) as users_count
                  from system_categories sc
                 order by sc.display_order
                """).query((rs, row) -> new Object[] {readSubcategory(rs), rs.getString("parent_name")}).list();

        Map<String, List<AdminCatalog.Subcategory>> byParent = new LinkedHashMap<>();
        Map<String, String> parentNames = new LinkedHashMap<>();
        for (Object[] row : rows) {
            AdminCatalog.Subcategory sub = (AdminCatalog.Subcategory) row[0];
            byParent.computeIfAbsent(sub.parentCode(), ignored -> new ArrayList<>()).add(sub);
            parentNames.putIfAbsent(sub.parentCode(), (String) row[1]);
        }
        List<AdminCatalog.CategoryGroup> groups = new ArrayList<>();
        byParent.forEach((code, subs) -> {
            AdminCatalog.Subcategory first = subs.get(0);
            groups.add(new AdminCatalog.CategoryGroup(code, parentNames.get(code), first.icon(), first.color(), first.order(), subs));
        });
        return groups;
    }

    @Transactional
    public AdminCatalog.Subcategory setCategoryActive(String code, boolean active, AdminActor actor) {
        AdminCatalog.Subcategory sub = jdbc.sql("""
                update system_categories sc set active = :active where code = :code
                returning sc.id, sc.code, sc.name, sc.parent_code, sc.icon, sc.color_light, sc.display_order, sc.active,
                          (select count(distinct uc.user_id) from user_categories uc
                            where uc.system_category_id = sc.id and uc.active) as users_count
                """).param("active", active).param("code", code).query(this::subcategory).optional()
                .orElseThrow(() -> new NotFoundException("La categoría no existe"));
        audit.adminAction(actor, active ? "Categoría activada" : "Categoría desactivada", null, sub.name(),
                Map.of("active", !active), Map.of("active", active, "code", code));
        return sub;
    }

    public String darkColor(String code) {
        return jdbc.sql("select color_dark from system_categories where code = :code").param("code", code)
                .query(String.class).optional().orElseThrow(() -> new NotFoundException("La categoría no existe"));
    }

    /** Siguiente display_order libre (es único en toda la tabla). */
    public int nextCategoryOrder() {
        return jdbc.sql("select coalesce(max(display_order), 0) + 1 from system_categories").query(Integer.class).single();
    }

    // ---------------------------------------------------------------- bancos

    public List<AdminCatalog.Bank> banks(String origin) {
        if ("CUSTOM".equals(origin)) {
            return jdbc.sql("""
                    select normalized_name, min(name) as name, min(country_code) as country_code, count(distinct user_id) as users_count
                      from banks where origin = 'CUSTOM'
                     group by normalized_name
                     order by users_count desc, name
                    """).query((rs, row) -> new AdminCatalog.Bank(rs.getString("normalized_name"), rs.getString("name"),
                    rs.getString("country_code"), "CUSTOM", null, true, List.of(), rs.getLong("users_count"))).list();
        }
        Map<UUID, List<AdminCatalog.Offering>> offerings = new LinkedHashMap<>();
        jdbc.sql("""
                select o.id, o.bank_id, o.card_type, o.brand, o.source_url, o.verified_at, o.active
                  from bank_card_offerings o join banks b on b.id = o.bank_id
                 where b.origin = 'SYSTEM'
                 order by o.card_type, o.brand
                """).query((rs, row) -> Map.entry(rs.getObject("bank_id", UUID.class), readOffering(rs))).list()
                .forEach(entry -> offerings.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue()));
        return jdbc.sql("select id, name, country_code, logo_url, active from banks where origin = 'SYSTEM' order by name")
                .query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new AdminCatalog.Bank(id.toString(), rs.getString("name"), rs.getString("country_code"), "SYSTEM",
                            rs.getString("logo_url"), rs.getBoolean("active"), offerings.getOrDefault(id, List.of()), 0);
                }).list();
    }

    public AdminCatalog.BankCounts bankCounts() {
        long system = jdbc.sql("select count(*) from banks where origin = 'SYSTEM'").query(Long.class).single();
        long custom = jdbc.sql("select count(distinct normalized_name) from banks where origin = 'CUSTOM'").query(Long.class).single();
        return new AdminCatalog.BankCounts(system, custom);
    }

    @Transactional
    public AdminCatalog.Bank setBankActive(UUID id, boolean active, AdminActor actor) {
        String name = jdbc.sql("update banks set active = :active where id = :id and origin = 'SYSTEM' returning name")
                .param("active", active).param("id", id).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("El banco no existe"));
        audit.adminAction(actor, active ? "Banco activado" : "Banco desactivado", null, name,
                Map.of("active", !active), Map.of("active", active));
        return systemBank(id);
    }

    @Transactional
    public AdminCatalog.Bank createBank(AdminCatalog.CreateBankRequest request, AdminActor actor) {
        String name = request.name().trim();
        UUID id;
        try {
            id = jdbc.sql("""
                    insert into banks (name, normalized_name, country_code, origin, active)
                    values (:name, :normalized, :country, 'SYSTEM', true)
                    returning id
                    """).param("name", name).param("normalized", normalize(name)).param("country", request.country())
                    .query(UUID.class).single();
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Ya existe un banco del sistema con ese nombre.");
        }
        audit.adminAction(actor, "Banco creado", null, name, null, Map.of("country", request.country()));
        return systemBank(id);
    }

    /** Débito sin marca y crédito con marca (regla de la base); se marca verificada hoy. */
    @Transactional
    public AdminCatalog.Bank addOffering(UUID bankId, AdminCatalog.CreateOfferingRequest request, AdminActor actor) {
        AdminCatalog.Bank bank = systemBank(bankId);
        boolean credit = "CREDIT".equals(request.cardType());
        if (credit && request.brand() == null) throw new IllegalArgumentException("Una tarjeta de crédito necesita marca");
        String brand = credit ? request.brand() : null;
        String source = request.sourceUrl() == null || request.sourceUrl().isBlank() ? null : request.sourceUrl().trim();
        try {
            jdbc.sql("""
                    insert into bank_card_offerings (bank_id, card_type, brand, source_url, verified_at, active)
                    values (:bankId, :cardType, :brand, :source, :verifiedAt, true)
                    """).param("bankId", bankId).param("cardType", request.cardType())
                    .param("brand", brand, java.sql.Types.VARCHAR).param("source", source, java.sql.Types.VARCHAR)
                    .param("verifiedAt", LocalDate.now()).update();
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Esa oferta ya existe para este banco.");
        }
        audit.adminAction(actor, "Oferta de tarjeta agregada", null, bank.name() + " · " + request.cardType()
                + (brand == null ? "" : " " + brand), null, Map.of("cardType", request.cardType()));
        return systemBank(bankId);
    }

    private AdminCatalog.Bank systemBank(UUID id) {
        return banks("SYSTEM").stream().filter(bank -> bank.id().equals(id.toString())).findFirst()
                .orElseThrow(() -> new NotFoundException("El banco no existe"));
    }

    // ---------------------------------------------------------------- monedas

    public List<AdminCatalog.Currency> currencies() {
        return jdbc.sql("""
                select code, numeric_code, name, symbol, decimal_places, is_base_currency, active
                  from currencies order by is_base_currency desc, active desc, code
                """).query(this::currency).list();
    }

    @Transactional
    public AdminCatalog.Currency setCurrencyActive(String code, boolean active, AdminActor actor) {
        AdminCatalog.Currency current = currencies().stream().filter(item -> item.code().equals(code)).findFirst()
                .orElseThrow(() -> new NotFoundException("La moneda no existe"));
        if (!active && current.base()) throw new ConflictException("La moneda base no se puede desactivar.");
        if (!active) {
            List<String> users = jdbc.sql("select name from countries where currency_code = :code and active")
                    .param("code", code).query(String.class).list();
            if (!users.isEmpty()) throw new ConflictException("La usa " + users.get(0) + ", que está activo.");
        }
        jdbc.sql("update currencies set active = :active where code = :code").param("active", active).param("code", code).update();
        audit.adminAction(actor, active ? "Moneda activada" : "Moneda desactivada", null, code,
                Map.of("active", !active), Map.of("active", active));
        return new AdminCatalog.Currency(current.code(), current.numericCode(), current.name(), current.symbol(),
                current.decimals(), current.base(), active);
    }

    @Transactional
    public AdminCatalog.Currency createCurrency(AdminCatalog.CreateCurrencyRequest request, AdminActor actor) {
        if (request.decimals() < 0 || request.decimals() > 4) throw new IllegalArgumentException("Los decimales van de 0 a 4");
        try {
            jdbc.sql("""
                    insert into currencies (code, numeric_code, name, symbol, decimal_places, active, is_base_currency)
                    values (:code, :numeric, :name, :symbol, :decimals, true, false)
                    """).param("code", request.code()).param("numeric", request.numericCode())
                    .param("name", request.name().trim()).param("symbol", request.symbol().trim())
                    .param("decimals", request.decimals()).update();
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Esa moneda ya existe.");
        }
        audit.adminAction(actor, "Moneda creada", null, request.code(), null, Map.of("name", request.name().trim()));
        return new AdminCatalog.Currency(request.code(), request.numericCode(), request.name().trim(), request.symbol().trim(),
                request.decimals(), false, true);
    }

    // ---------------------------------------------------------------- países

    public List<AdminCatalog.Country> countries() {
        return jdbc.sql("""
                select c.code, c.name, c.currency_code, c.active,
                       (select count(*) from app_users u where u.country_code = c.code) as users_count
                  from countries c order by c.active desc, users_count desc, c.name
                """).query((rs, row) -> new AdminCatalog.Country(rs.getString("code"), rs.getString("name"),
                rs.getString("currency_code").trim(), rs.getBoolean("active"), rs.getLong("users_count"))).list();
    }

    @Transactional
    public AdminCatalog.Country setCountryActive(String code, boolean active, AdminActor actor) {
        AdminCatalog.Country current = countries().stream().filter(item -> item.code().equals(code)).findFirst()
                .orElseThrow(() -> new NotFoundException("El país no existe"));
        if (active) ensureCurrencyActive(current.currencyCode());
        jdbc.sql("update countries set active = :active where code = :code").param("active", active).param("code", code).update();
        audit.adminAction(actor, active ? "País activado" : "País desactivado", null, current.name(),
                Map.of("active", !active), Map.of("active", active));
        return new AdminCatalog.Country(current.code(), current.name(), current.currencyCode(), active, current.usersCount());
    }

    @Transactional
    public AdminCatalog.Country createCountry(AdminCatalog.CreateCountryRequest request, AdminActor actor) {
        ensureCurrencyActive(request.currencyCode());
        try {
            jdbc.sql("insert into countries (code, name, currency_code, active) values (:code, :name, :currency, true)")
                    .param("code", request.code()).param("name", request.name().trim())
                    .param("currency", request.currencyCode()).update();
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Ese país ya existe.");
        }
        audit.adminAction(actor, "País creado", null, request.name().trim(), null,
                Map.of("code", request.code(), "currency", request.currencyCode()));
        return new AdminCatalog.Country(request.code(), request.name().trim(), request.currencyCode(), true, 0);
    }

    private void ensureCurrencyActive(String currencyCode) {
        Boolean active = jdbc.sql("select active from currencies where code = :code").param("code", currencyCode)
                .query(Boolean.class).optional().orElseThrow(() -> new IllegalArgumentException("La moneda " + currencyCode + " no existe"));
        if (!active) throw new ConflictException("Activa primero la moneda " + currencyCode + ".");
    }

    // ---------------------------------------------------------------- mapeos

    private AdminCatalog.Subcategory subcategory(ResultSet rs, int row) throws SQLException {
        return readSubcategory(rs);
    }

    private AdminCatalog.Subcategory readSubcategory(ResultSet rs) throws SQLException {
        return new AdminCatalog.Subcategory(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("parent_code"), rs.getString("icon"), rs.getString("color_light"), rs.getInt("display_order"),
                rs.getBoolean("active"), rs.getLong("users_count"));
    }

    private AdminCatalog.Offering readOffering(ResultSet rs) throws SQLException {
        return new AdminCatalog.Offering(rs.getObject("id", UUID.class), rs.getString("card_type"), rs.getString("brand"),
                rs.getString("source_url"), rs.getObject("verified_at", LocalDate.class), rs.getBoolean("active"));
    }

    private AdminCatalog.Currency currency(ResultSet rs, int row) throws SQLException {
        return new AdminCatalog.Currency(rs.getString("code").trim(), rs.getString("numeric_code").trim(), rs.getString("name"),
                rs.getString("symbol"), rs.getInt("decimal_places"), rs.getBoolean("is_base_currency"), rs.getBoolean("active"));
    }

    /** Igual que CategoryService.normalize: sin tildes, sin espacios extremos y en mayúsculas. */
    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").trim().toUpperCase(Locale.ROOT);
    }
}
