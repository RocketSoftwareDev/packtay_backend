package ec.paktay.auth.service;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ec.paktay.auth.dto.BlockRequest;
import ec.paktay.auth.dto.BlockResponse;
import ec.paktay.auth.exception.ConflictException;
import ec.paktay.auth.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Bloqueos de correo, dominio o IP (blocked_identities, V9).
 *
 * - TICKETS: business-svc no guarda tickets del valor bloqueado.
 * - REGISTRATION: el registro responde un error genérico ({@link #isRegistrationBlocked}).
 * - ACCOUNT (solo correo): si el correo tiene cuenta, se bloquea como desde Usuarios.
 *
 * Quitar un bloqueo con ACCOUNT desbloquea también esa cuenta.
 */
@Service
public class IdentityBlockService {
    private static final Logger log = LoggerFactory.getLogger(IdentityBlockService.class);

    private final JdbcTemplate db;
    private final KeycloakIdentityService identities;
    private final AdminAccountService accounts;
    private final AdminAuditWriter audit;

    public IdentityBlockService(JdbcTemplate db, KeycloakIdentityService identities, AdminAccountService accounts,
                                AdminAuditWriter audit) {
        this.db = db;
        this.identities = identities;
        this.accounts = accounts;
        this.audit = audit;
    }

    public List<BlockResponse> list(String type, String query) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select * from blocked_identities where true");
        if (type != null && !type.isBlank() && !"ALL".equals(type)) {
            sql.append(" and type = ?");
            args.add(type);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" and value ilike ?");
            args.add("%" + query.trim().toLowerCase() + "%");
        }
        sql.append(" order by created_at desc limit 500");
        return db.query(sql.toString(), this::map, args.toArray());
    }

    public BlockResponse create(BlockRequest request, AdminActor actor, String lastTicketIp) {
        String value = valueFor(request.type(), request.value());
        List<String> scopes = scopesFor(request.type(), request.scopes());
        Map<?, ?> account = "EMAIL".equals(request.type()) && scopes.contains("ACCOUNT") ? findAccount(request.value()) : null;
        if (account != null && String.valueOf(account.get("id")).equals(actor.id())) {
            throw new ConflictException("No puedes bloquear tu propia cuenta.");
        }
        BlockResponse created;
        try {
            created = db.queryForObject("""
                    insert into blocked_identities (type, value, scopes, reason, automatic, created_by)
                    values (?, ?, cast(? as character varying[]), ?, false, ?)
                    returning *
                    """, this::map, request.type(), value, toArrayLiteral(scopes), request.reason(), actor.label());
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("Ese valor ya está bloqueado.");
        }
        audit.adminAction(actor, blockAction(request.type()), null, value + " · " + String.join(", ", scopes),
                null, Map.of("type", request.type(), "reason", request.reason(), "scopes", scopes));

        if (account != null) accounts.block(String.valueOf(account.get("id")), actor);
        if ("EMAIL".equals(request.type()) && request.alsoBlockIp() && lastTicketIp != null) {
            db.update("""
                    insert into blocked_identities (type, value, scopes, reason, automatic, created_by)
                    values ('IP', ?, array['TICKETS']::character varying[], ?, false, ?)
                    on conflict (type, value) do nothing
                    """, lastTicketIp, request.reason(), actor.label());
        }
        return created;
    }

    public void delete(UUID id, AdminActor actor) {
        List<BlockResponse> found = db.query("select * from blocked_identities where id = ?", this::map, id);
        if (found.isEmpty()) throw new NotFoundException("El bloqueo no existe");
        BlockResponse block = found.get(0);
        db.update("delete from blocked_identities where id = ?", id);
        audit.adminAction(actor, "Bloqueo quitado", null, block.value(), Map.of("type", block.type(), "scopes", block.scopes()), null);
        if ("EMAIL".equals(block.type()) && block.scopes().contains("ACCOUNT")) {
            Map<?, ?> user = findAccount(block.value());
            if (user != null) accounts.unblock(String.valueOf(user.get("id")), actor);
        }
    }

    /** Registro: correo exacto (normalizado) o su dominio bloqueados para REGISTRATION. */
    public boolean isRegistrationBlocked(String email) {
        Boolean blocked = db.queryForObject("""
                select exists (
                    select 1 from blocked_identities
                     where 'REGISTRATION' = any(scopes)
                       and ((type = 'EMAIL' and value = ?) or (type = 'DOMAIN' and value = ?))
                )
                """, Boolean.class, EmailRules.normalize(email), EmailRules.domain(email));
        return Boolean.TRUE.equals(blocked);
    }

    /** IP del último ticket de ese correo (para "bloquear también la IP"). */
    public String lastTicketIp(String email) {
        List<String> ips = db.queryForList("""
                select client_ip from support_tickets
                 where email_normalized = ? and client_ip is not null
                 order by created_at desc limit 1
                """, String.class, EmailRules.normalize(email));
        return ips.isEmpty() ? null : ips.get(0);
    }

    /** Cuenta con ese correo: primero tal cual, después por el correo normalizado en app_users. */
    private Map<?, ?> findAccount(String email) {
        Map<?, ?> user = identities.findByEmail(email.trim().toLowerCase());
        if (user != null) return user;
        List<String> ids = db.queryForList("select id::text from app_users where public.normalize_email(email) = ? limit 1",
                String.class, EmailRules.normalize(email));
        return ids.isEmpty() ? null : identities.findById(ids.get(0));
    }

    static String valueFor(String type, String raw) {
        String clean = raw.trim().toLowerCase();
        return switch (type) {
            case "EMAIL" -> {
                if (!clean.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("No es un correo válido");
                yield EmailRules.normalize(clean);
            }
            case "DOMAIN" -> {
                String domain = clean.contains("@") ? EmailRules.domain(clean) : clean;
                if (!domain.matches("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")) throw new IllegalArgumentException("No es un dominio válido");
                if (EmailRules.isPublicDomain(domain)) {
                    throw new IllegalArgumentException(domain + " es un dominio público: bloquea el correo exacto.");
                }
                yield domain;
            }
            default -> {
                if (!clean.matches("^[0-9a-f.:]{3,64}$")) throw new IllegalArgumentException("No es una IP válida");
                yield clean;
            }
        };
    }

    /** TICKETS siempre; ACCOUNT solo para correos; una IP solo bloquea tickets. */
    static List<String> scopesFor(String type, List<String> requested) {
        List<String> scopes = new ArrayList<>(List.of("TICKETS"));
        if (!"IP".equals(type) && requested.contains("REGISTRATION")) scopes.add("REGISTRATION");
        if ("EMAIL".equals(type) && requested.contains("ACCOUNT")) scopes.add("ACCOUNT");
        return scopes;
    }

    private static String blockAction(String type) {
        return switch (type) {
            case "EMAIL" -> "Correo bloqueado";
            case "DOMAIN" -> "Dominio bloqueado";
            default -> "IP bloqueada";
        };
    }

    private static String toArrayLiteral(List<String> scopes) {
        return "{" + String.join(",", scopes) + "}";
    }

    private BlockResponse map(ResultSet rs, int row) throws SQLException {
        Array array = rs.getArray("scopes");
        List<String> scopes = array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
        return new BlockResponse(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("value"), scopes,
                rs.getString("reason"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by"),
                rs.getBoolean("automatic"));
    }
}
