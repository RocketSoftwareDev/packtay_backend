package ec.paktay.auth.service;

import java.util.Locale;
import java.util.Set;

/**
 * Misma regla que business-svc (SupportEmails) y que public.normalize_email (V9):
 * minúsculas, sin alias "+" y sin puntos en Gmail. Si una cambia, cambian las tres.
 */
public final class EmailRules {
    private static final Set<String> GMAIL = Set.of("gmail.com", "googlemail.com");
    private static final Set<String> PUBLIC_DOMAINS = Set.of("gmail.com", "googlemail.com", "hotmail.com", "outlook.com",
            "live.com", "yahoo.com", "icloud.com", "me.com", "proton.me", "protonmail.com");

    private EmailRules() {
    }

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

    public static boolean isPublicDomain(String domain) {
        return domain != null && PUBLIC_DOMAINS.contains(domain.trim().toLowerCase(Locale.ROOT));
    }

    /** "id anónimo 7f3a…c21": lo que queda en la bitácora de una cuenta eliminada. */
    public static String anonymousId(String userId) {
        if (userId == null || userId.length() < 8) return "id anónimo";
        return "id anónimo " + userId.substring(0, 4) + "…" + userId.substring(userId.length() - 3);
    }
}
