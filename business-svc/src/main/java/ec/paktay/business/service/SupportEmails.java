package ec.paktay.business.service;

import java.util.Locale;
import java.util.Set;

/**
 * Reglas de correo de soporte. normalize es la misma regla que public.normalize_email (V9)
 * y que auth-svc al revisar bloqueos en el registro: si una cambia, cambian las tres.
 */
public final class SupportEmails {
    private static final Set<String> GMAIL = Set.of("gmail.com", "googlemail.com");
    private static final Set<String> PUBLIC_DOMAINS = Set.of("gmail.com", "googlemail.com", "hotmail.com", "outlook.com",
            "live.com", "yahoo.com", "icloud.com", "me.com", "proton.me", "protonmail.com");

    private SupportEmails() {
    }

    /** troll+1@gmail.com, t.roll@gmail.com y TROLL@googlemail.com → troll@gmail.com. */
    public static String normalize(String email) {
        if (email == null) return null;
        String clean = email.trim().toLowerCase(Locale.ROOT);
        int at = clean.indexOf('@');
        if (at < 0) return clean;
        String local = clean.substring(0, at);
        String domain = clean.substring(at + 1);
        if ("googlemail.com".equals(domain)) domain = "gmail.com";
        int plus = local.indexOf('+');
        if (plus >= 0) local = local.substring(0, plus);
        if (GMAIL.contains(domain)) local = local.replace(".", "");
        return local + "@" + domain;
    }

    public static String domain(String email) {
        if (email == null) return "";
        String clean = email.trim().toLowerCase(Locale.ROOT);
        int at = clean.indexOf('@');
        return at < 0 ? clean : clean.substring(at + 1);
    }

    /** Bloquear gmail.com entero dejaría fuera a casi todos: solo se permite el correo exacto. */
    public static boolean isPublicDomain(String domain) {
        return domain != null && PUBLIC_DOMAINS.contains(domain.trim().toLowerCase(Locale.ROOT));
    }
}
