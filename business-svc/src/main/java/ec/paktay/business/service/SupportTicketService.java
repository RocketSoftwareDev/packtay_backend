package ec.paktay.business.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import ec.paktay.business.dto.admin.AdminActor;
import ec.paktay.business.dto.support.PublicTicketRequest;
import ec.paktay.business.dto.support.PublicTicketResponse;
import ec.paktay.business.dto.support.SupportCounts;
import ec.paktay.business.dto.support.TicketDetail;
import ec.paktay.business.dto.support.TicketReplyRequest;
import ec.paktay.business.dto.support.TicketSummary;
import ec.paktay.business.exception.NotFoundException;
import ec.paktay.business.exception.TooManyRequestsException;
import ec.paktay.business.exception.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tickets de soporte.
 *
 * Público: el formulario crea el ticket PENDING_VERIFICATION y manda un enlace de
 * confirmación; solo al confirmarlo pasa a NEW y aparece en el panel. Un bot o alguien
 * que usa un correo ajeno nunca confirma. Un correo, dominio o IP bloqueado recibe la
 * misma respuesta que los demás, pero no se guarda nada ni se envía correo.
 *
 * Panel: bandeja, conversación, respuesta por correo, nota interna y estado.
 */
@Service
public class SupportTicketService {
    static final int IP_LIMIT_PER_HOUR = 5;
    static final int EMAIL_LIMIT_PER_DAY = 3;
    /** Intentos por hora desde una IP que la bloquean automáticamente. */
    static final int IP_AUTO_BLOCK_PER_HOUR = 20;
    static final Duration VERIFY_WINDOW = Duration.ofHours(48);
    static final String ACCEPTED_MESSAGE = "Te enviamos un correo para confirmar tu solicitud. Revisa tu bandeja y spam.";

    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final SupportMailService mail;
    private final SupportRateLimiter limiter;
    private final TransactionTemplate tx;

    public SupportTicketService(JdbcClient jdbc, SupportMailService mail, SupportRateLimiter limiter,
                                PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.limiter = limiter;
        this.tx = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------ público

    public PublicTicketResponse create(PublicTicketRequest request, String ip, String userAgent) {
        String email = request.email().trim();
        String normalized = SupportEmails.normalize(email);

        if (request.website() != null && !request.website().isBlank()) {
            log.warn("support_honeypot_filled ip={}", ip);
            return accepted(fakeCode());
        }
        if (!limiter.tryAcquire("ip:" + ip, IP_LIMIT_PER_HOUR, Duration.ofHours(1))) {
            if (limiter.count("ip:" + ip, Duration.ofHours(1)) >= IP_AUTO_BLOCK_PER_HOUR) autoBlockIp(ip);
            throw new TooManyRequestsException("Demasiadas solicitudes. Intenta más tarde.");
        }
        if (!limiter.tryAcquire("email:" + normalized, EMAIL_LIMIT_PER_DAY, Duration.ofDays(1))) {
            throw new TooManyRequestsException("Ya recibimos varias solicitudes de este correo hoy. Revisa tu bandeja.");
        }
        if (isBlocked(normalized, SupportEmails.domain(email), ip)) {
            log.info("support_ticket_blocked ip={}", ip);
            return accepted(fakeCode());
        }

        String token = newToken();
        String code = tx.execute(status -> {
            String created = jdbc.sql("""
                    insert into support_tickets (email, email_normalized, reason, verify_token_hash, verify_expires_at, client_ip, user_agent)
                    values (:email, :normalized, :reason, :hash, now() + make_interval(hours => :hours), :ip, :agent)
                    returning code
                    """).param("email", email).param("normalized", normalized).param("reason", request.reason().trim())
                    .param("hash", sha256(token)).param("hours", (int) VERIFY_WINDOW.toHours()).param("ip", ip)
                    .param("agent", truncate(userAgent, 300), java.sql.Types.VARCHAR)
                    .query(String.class).single();
            jdbc.sql("""
                    insert into support_messages (ticket_id, author, author_label, body)
                    select id, 'USER', email, reason from support_tickets where code = :code
                    """).param("code", created).update();
            return created;
        });
        try {
            mail.sendVerification(email, code, token);
        } catch (RuntimeException ex) {
            // El ticket queda sin confirmar y se borra solo a las 48 h; el usuario puede reintentar.
            log.error("support_verification_mail_failed code={} reason={}", code, ex.getMessage());
        }
        log.info("support_ticket_created code={} ip={}", code, ip);
        return accepted(code);
    }

    /** Confirma el correo. Devuelve el código del ticket o vacío si el enlace no sirve (vencido o usado). */
    @Transactional
    public Optional<String> verify(String token) {
        if (token == null || token.isBlank() || token.length() > 100) return Optional.empty();
        Optional<Map.Entry<UUID, String>> ticket = jdbc.sql("""
                update support_tickets
                   set status = 'NEW', verified_at = now(), verify_token_hash = null, updated_at = now()
                 where verify_token_hash = :hash and status = 'PENDING_VERIFICATION' and verify_expires_at > now()
                returning id, code
                """).param("hash", sha256(token))
                .query((rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("code"))).optional();
        ticket.ifPresent(found -> jdbc.sql("""
                insert into support_messages (ticket_id, author, author_label, body)
                values (:id, 'SYSTEM', 'Sistema', :body)
                """).param("id", found.getKey()).param("body", "Correo verificado, ticket " + found.getValue() + " creado").update());
        return ticket.map(Map.Entry::getValue);
    }

    // ------------------------------------------------------------------ panel

    public SupportCounts counts() {
        Map<String, Long> byStatus = new java.util.HashMap<>();
        jdbc.sql("select status, count(*) as total from support_tickets group by status")
                .query((rs, row) -> Map.entry(rs.getString("status"), rs.getLong("total"))).list()
                .forEach(entry -> byStatus.put(entry.getKey(), entry.getValue()));
        long blocks = jdbc.sql("select count(*) from blocked_identities").query(Long.class).single();
        return new SupportCounts(byStatus.getOrDefault("NEW", 0L), byStatus.getOrDefault("IN_PROGRESS", 0L),
                byStatus.getOrDefault("RESOLVED", 0L), byStatus.getOrDefault("PENDING_VERIFICATION", 0L), blocks);
    }

    public List<TicketSummary> list(String status, String query) {
        String pattern = query == null || query.isBlank() ? null : "%" + query.trim() + "%";
        return jdbc.sql(SELECT_TICKET + """
                 where t.status = :status
                   and (cast(:pattern as text) is null or t.email ilike cast(:pattern as text)
                        or t.reason ilike cast(:pattern as text) or t.code ilike cast(:pattern as text))
                 order by t.created_at desc
                 limit 200
                """).param("status", status).param("pattern", pattern, java.sql.Types.VARCHAR)
                .query(this::summary).list();
    }

    @Transactional
    public TicketDetail get(UUID id) {
        TicketSummary ticket = find(id);
        jdbc.sql("update support_tickets set unread = false where id = :id and unread").param("id", id).update();
        return TicketDetail.of(ticket, messages(id));
    }

    /**
     * Respuesta o nota interna. La respuesta se envía primero por correo: si el correo
     * falla no se guarda nada y el panel muestra el error (502) para reintentar.
     */
    @Transactional
    public TicketDetail reply(UUID id, TicketReplyRequest request, AdminActor actor) {
        TicketSummary ticket = find(id);
        boolean note = "NOTE".equals(request.kind());
        String body = request.body().trim();
        if (!note) {
            try {
                mail.sendReply(ticket.email(), ticket.code(), body);
            } catch (RuntimeException ex) {
                throw new UpstreamException("No se pudo enviar el correo. Intenta de nuevo.", ex);
            }
        }
        jdbc.sql("insert into support_messages (ticket_id, author, author_label, body) values (:id, :author, :label, :body)")
                .param("id", id).param("author", note ? "NOTE" : "ADMIN").param("label", actor.label())
                .param("body", body).update();
        if (!note) updateStatus(id, request.resolve() ? "RESOLVED" : "IN_PROGRESS");
        return TicketDetail.of(find(id), messages(id));
    }

    @Transactional
    public TicketDetail setStatus(UUID id, String status) {
        find(id);
        updateStatus(id, status);
        return TicketDetail.of(find(id), messages(id));
    }

    /** Los tickets que nadie confirmó en 48 h se borran (con sus mensajes, en cascada). */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void purgeUnverified() {
        int deleted = jdbc.sql("""
                delete from support_tickets
                 where status = 'PENDING_VERIFICATION' and created_at < now() - make_interval(hours => :hours)
                """).param("hours", (int) VERIFY_WINDOW.toHours()).update();
        log.info("support_unverified_purged rows={}", deleted);
    }

    // ------------------------------------------------------------------ internos

    /** Ticket + cuenta con el mismo correo (normalizado), su plan y su último acceso. */
    private static final String SELECT_TICKET = """
            select t.id, t.code, t.email, t.reason, t.status, t.created_at, t.verified_at, t.unread,
                   u.id as user_id, coalesce(nullif(u.display_name, ''), u.email) as user_name,
                   coalesce(s.plan, 'PRO') as plan, s.source as plan_source,
                   (select max(last_authenticated_at) from user_devices d where d.user_id = u.id) as last_access
              from support_tickets t
              left join app_users u on public.normalize_email(u.email) = t.email_normalized
              left join user_subscription s on s.user_id = u.id and s.status = 'ACTIVE'
            """;

    private TicketSummary find(UUID id) {
        return jdbc.sql(SELECT_TICKET + " where t.id = :id and t.status <> 'PENDING_VERIFICATION'")
                .param("id", id).query(this::summary).optional()
                .orElseThrow(() -> new NotFoundException("El ticket no existe"));
    }

    private List<TicketDetail.Message> messages(UUID ticketId) {
        return jdbc.sql("select id, author, author_label, body, created_at from support_messages where ticket_id = :id order by created_at")
                .param("id", ticketId).query((rs, row) -> new TicketDetail.Message(rs.getObject("id", UUID.class),
                        rs.getString("author"), rs.getString("author_label"), rs.getString("body"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private void updateStatus(UUID id, String status) {
        jdbc.sql("""
                update support_tickets
                   set status = :status, updated_at = now(),
                       resolved_at = case when :status = 'RESOLVED' then now() else null end
                 where id = :id
                """).param("status", status).param("id", id).update();
    }

    private boolean isBlocked(String normalizedEmail, String domain, String ip) {
        return jdbc.sql("""
                select exists (
                    select 1 from blocked_identities
                     where 'TICKETS' = any(scopes)
                       and ((type = 'EMAIL' and value = :email) or (type = 'DOMAIN' and value = :domain) or (type = 'IP' and value = :ip))
                )
                """).param("email", normalizedEmail).param("domain", domain).param("ip", ip).query(Boolean.class).single();
    }

    private void autoBlockIp(String ip) {
        jdbc.sql("""
                insert into blocked_identities (type, value, scopes, reason, automatic, created_by)
                values ('IP', :ip, array['TICKETS']::character varying[], 'SPAM', true, 'automático')
                on conflict (type, value) do nothing
                """).param("ip", ip).update();
        log.warn("support_ip_auto_blocked ip={}", ip);
    }

    private TicketSummary summary(ResultSet rs, int row) throws SQLException {
        UUID userId = rs.getObject("user_id", UUID.class);
        TicketSummary.TicketAccount account = userId == null ? null : new TicketSummary.TicketAccount(userId,
                rs.getString("user_name"), planLabel(rs.getString("plan"), rs.getString("plan_source")),
                rs.getObject("last_access", OffsetDateTime.class));
        return new TicketSummary(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("email"),
                rs.getString("reason"), rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("verified_at", OffsetDateTime.class), rs.getBoolean("unread"), account);
    }

    static String planLabel(String plan, String source) {
        if (source == null) return "PRO".equals(plan) ? "PRO · Beta" : plan;
        return switch (source) {
            case "APP_STORE" -> plan + " · App Store";
            case "PLAY_STORE" -> plan + " · Play Store";
            default -> plan + " · Tester";
        };
    }

    private static PublicTicketResponse accepted(String code) {
        return new PublicTicketResponse(code, "PENDING_VERIFICATION", ACCEPTED_MESSAGE);
    }

    /** Código con la misma forma que uno real, para no revelar que el correo está bloqueado. */
    private static String fakeCode() {
        return "PK-" + (1000 + RANDOM.nextInt(9000));
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
