package ec.paktay.auth.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordPinService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final KeycloakIdentityService identities;
    private final PasswordMailService mail;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PasswordPinService(JdbcTemplate db, org.springframework.transaction.PlatformTransactionManager manager,
            KeycloakIdentityService identities, PasswordMailService mail) {
        this(db, new TransactionTemplate(manager), identities, mail, Clock.systemUTC());
    }
    PasswordPinService(JdbcTemplate db, TransactionTemplate tx, KeycloakIdentityService identities,
            PasswordMailService mail, Clock clock) {
        this.db = db; this.tx = tx; this.identities = identities; this.mail = mail; this.clock = clock;
    }
    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    public void requestReset(String email) { request(normalize(email), "RESET", null); }
    public void requestChange(String subject) {
        Map<?, ?> user = identities.findById(subject);
        if (user == null || !Boolean.TRUE.equals(user.get("enabled")) || !(user.get("email") instanceof String email) || email.isBlank())
            throw new IllegalArgumentException("La cuenta no tiene un correo disponible");
        request(normalize(email), "CHANGE", subject);
    }
    private Map<?, ?> recipient(String email) {
        Map<?, ?> user = identities.findByEmail(email);
        if (user == null) {
            var ids = db.queryForList("select id::text from app_users where lower(trim(email)) = ? and status = 'ACTIVE'", String.class, email);
            if (ids.size() == 1) user = identities.findById(ids.get(0));
        }
        // Un correo local antiguo nunca autoriza cambiar la cuenta de otro correo.
        return user != null && Boolean.TRUE.equals(user.get("enabled"))
                && email.equalsIgnoreCase(String.valueOf(user.get("email"))) ? user : null;
    }
    private void request(String email, String purpose, String subject) {
        tx.executeWithoutResult(status -> {
            db.update("insert into password_pins(email,purpose) values (?,?) on conflict do nothing", email, purpose);
            Map<String,Object> row = db.queryForMap("select * from password_pins where email=? and purpose=? for update", email, purpose);
            long now = clock.millis();
            long window = ((Number) row.get("window_at")).longValue();
            int requests = now - window >= 3600000 ? 0 : ((Number) row.get("requests")).intValue();
            if (now - ((Number)row.get("sent_at")).longValue() < 60000 || requests >= 5) return;
            if (requests == 0) window = now;
            Map<?, ?> user = recipient(email);
            String userId = user == null ? null : String.valueOf(user.get("id"));
            if (subject != null && !subject.equals(userId)) throw new IllegalArgumentException("No se pudo verificar el correo de la cuenta");
            String pin = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
            db.update("update password_pins set user_id=?,pin_hash=?,expires_at=?,sent_at=?,window_at=?,requests=?,attempts=0,token_hash=null,token_expires_at=0 where email=? and purpose=?",
                    userId, userId == null ? null : encoder.encode(pin), now + 900000, now, window, requests + 1, email, purpose);
            if (userId != null) mail.sendPin(email, pin, purpose);
        });
    }
    public String verifyReset(String email, String pin) { return verify(normalize(email), "RESET", null, pin); }
    public String verifyChange(String subject, String pin) { return verify(accountEmail(subject), "CHANGE", subject, pin); }
    private String accountEmail(String subject) {
        Map<?, ?> user = identities.findById(subject);
        if (user == null || !(user.get("email") instanceof String email)) throw new IllegalArgumentException("Cuenta no disponible");
        return normalize(email);
    }
    private String verify(String email, String purpose, String subject, String pin) {
        String token = tx.execute(status -> {
            var rows = db.queryForList("select * from password_pins where email=? and purpose=? for update", email, purpose);
            if (rows.isEmpty()) return null;
            var row = rows.get(0);
            if (row.get("pin_hash") == null || ((Number)row.get("expires_at")).longValue() <= clock.millis()
                    || ((Number)row.get("attempts")).intValue() >= 5) return null;
            db.update("update password_pins set attempts=attempts+1 where email=? and purpose=?", email, purpose);
            if (!encoder.matches(pin, (String)row.get("pin_hash")) || (subject != null && !subject.equals(row.get("user_id")))) return null;
            if (!currentIdentity(row, email)) return null;
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String proof = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            db.update("update password_pins set pin_hash=null,token_hash=?,token_expires_at=? where email=? and purpose=?",
                    hash(proof), clock.millis() + 600000, email, purpose);
            return proof;
        });
        // El error se emite después del commit para conservar los intentos fallidos.
        if (token == null) throw new IllegalArgumentException("PIN inválido o expirado. Solicita uno nuevo si agotaste los 5 intentos.");
        return token;
    }
    private boolean currentIdentity(Map<String,Object> row, String email) {
        Map<?, ?> user = identities.findById((String)row.get("user_id"));
        return user != null && Boolean.TRUE.equals(user.get("enabled")) && email.equalsIgnoreCase(String.valueOf(user.get("email")));
    }
    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 no disponible"); }
    }
    public void completeReset(String email, String resetToken, String password) { complete(normalize(email), "RESET", null, resetToken, password); }
    public void completeChange(String subject, String resetToken, String password) { complete(accountEmail(subject), "CHANGE", subject, resetToken, password); }
    private void complete(String email, String purpose, String subject, String resetToken, String password) {
        Boolean valid = tx.execute(status -> {
            var rows = db.queryForList("select * from password_pins where email=? and purpose=? for update", email, purpose);
            if (rows.isEmpty()) return false;
            var row = rows.get(0);
            if (row.get("token_hash") == null || ((Number)row.get("token_expires_at")).longValue() <= clock.millis()
                    || !java.security.MessageDigest.isEqual(hash(resetToken).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                            ((String)row.get("token_hash")).getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    || (subject != null && !subject.equals(row.get("user_id"))) || !currentIdentity(row, email)) return false;
            identities.replacePassword((String)row.get("user_id"), password, false);
            db.update("update password_pins set token_hash=null,token_expires_at=0 where email=? and purpose=?", email, purpose);
            return true;
        });
        if (!Boolean.TRUE.equals(valid)) throw new IllegalArgumentException("La validación expiró. Solicita y valida un nuevo PIN.");
    }
}
