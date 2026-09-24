package ec.paktay.business.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Normalización de comercios y clave de las reglas comercio → categoría.
 *
 * La clave (normalization_version = 2) es el comercio hasta el primer bloque que
 * tiene números, con un máximo de dos palabras: "FYBECA 123 QUITO" → "FYBECA",
 * "PAYPAL *SPOTIFYAB" → "PAYPAL SPOTIFYAB". Así la sucursal o el número de caja
 * no parten una regla en varias. Misma lógica que la función SQL
 * merchant_rule_key (V6) y que ruleKeyOf en el teléfono.
 */
public final class MerchantKey {
    public static final int RULE_VERSION = 2;
    private static final int MAX_WORDS = 2;

    private MerchantKey() { }

    /** Mayúsculas, sin tildes ni símbolos, un espacio entre palabras. */
    public static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    /** Clave de la regla, o null si el comercio no tiene letras ni números. */
    public static String ruleKey(String merchant) {
        String normalized = normalize(merchant);
        if (normalized.isEmpty()) return null;
        String[] words = normalized.split(" ");
        List<String> kept = new ArrayList<>();
        for (String word : words) {
            if (word.matches(".*[0-9].*")) break;
            kept.add(word);
            if (kept.size() == MAX_WORDS) break;
        }
        if (kept.isEmpty()) {
            for (String word : words) {
                String letters = word.replaceAll("[0-9]", "");
                if (!letters.isEmpty()) kept.add(letters);
                if (kept.size() == MAX_WORDS) break;
            }
        }
        return kept.isEmpty() ? normalized : String.join(" ", kept);
    }
}
