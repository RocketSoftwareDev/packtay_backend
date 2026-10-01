package ec.paktay.auth.service;

import java.util.Locale;
import java.util.Set;

import ec.paktay.auth.exception.NotFoundException;

/** Proveedores con los que se puede entrar (día 8c): emisor y llaves públicas de su ID token. */
public enum SocialProvider {
    APPLE("Apple", Set.of("https://appleid.apple.com"), "https://appleid.apple.com/auth/keys"),
    GOOGLE("Google", Set.of("https://accounts.google.com", "accounts.google.com"), "https://www.googleapis.com/oauth2/v3/certs");

    private final String label;
    private final Set<String> issuers;
    private final String jwksUrl;

    SocialProvider(String label, Set<String> issuers, String jwksUrl) {
        this.label = label;
        this.issuers = issuers;
        this.jwksUrl = jwksUrl;
    }

    public String label() {
        return label;
    }

    public Set<String> issuers() {
        return issuers;
    }

    public String jwksUrl() {
        return jwksUrl;
    }

    /** «apple» o «google» de la ruta; cualquier otra cosa es 404. */
    public static SocialProvider fromPath(String path) {
        try {
            return valueOf(path.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new NotFoundException("Proveedor no disponible");
        }
    }
}
