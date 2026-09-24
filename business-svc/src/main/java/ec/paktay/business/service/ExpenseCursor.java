package ec.paktay.business.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Cursor opaco de la paginación por clave (keyset) del historial de gastos.
 *
 * Codifica el modo, el instante de ordenación y el id de la última fila entregada,
 * en Base64 URL-safe sin relleno: {@code o|<occurred_at>|<id>} para el historial
 * normal (occurred_at desc, id desc) y {@code u|<updated_at>|<id>} para la
 * sincronización incremental con since (updated_at asc, id asc). Un cursor de un
 * modo no vale en el otro. Sin dependencias de Spring ni de la base: se prueba
 * con pruebas unitarias puras.
 */
public record ExpenseCursor(Mode mode, Instant position, UUID id) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private static final String INVALID = "El cursor no es válido para esta consulta";

    public enum Mode {
        /** Historial: occurred_at desc, id desc. */
        OCCURRED("o"),
        /** Sincronización incremental (since): updated_at asc, id asc. */
        UPDATED("u");

        private final String tag;

        Mode(String tag) { this.tag = tag; }

        static Mode fromTag(String tag) {
            for (Mode mode : values()) {
                if (mode.tag.equals(tag)) return mode;
            }
            throw new IllegalArgumentException(INVALID);
        }
    }

    public ExpenseCursor {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(id, "id");
    }

    public String encode() {
        String plain = mode.tag + "|" + position + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodifica un cursor recibido del cliente. Cualquier formato inesperado, o un
     * cursor de otro modo, termina en IllegalArgumentException (HTTP 400).
     */
    public static ExpenseCursor decode(String raw, Mode expected) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(INVALID);
        try {
            String plain = new String(Base64.getUrlDecoder().decode(raw.strip()), StandardCharsets.UTF_8);
            String[] parts = plain.split("\\|", -1);
            if (parts.length != 3) throw new IllegalArgumentException(INVALID);
            Mode mode = Mode.fromTag(parts[0]);
            if (mode != expected) throw new IllegalArgumentException(INVALID);
            return new ExpenseCursor(mode, Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(INVALID, ex);
        }
    }

    /** limit ausente = 50; fuera de 1..200 es un error del cliente (HTTP 400). */
    public static int resolveLimit(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit debe estar entre 1 y " + MAX_LIMIT);
        }
        return limit;
    }
}
