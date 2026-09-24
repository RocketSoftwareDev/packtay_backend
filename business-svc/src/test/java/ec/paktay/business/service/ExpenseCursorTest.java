package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pruebas unitarias puras (sin base ni contexto de Spring) del cursor y del límite de página. */
class ExpenseCursorTest {
    private static final UUID ID = UUID.fromString("0b6f1e9c-3c1d-4d5e-9f10-2a3b4c5d6e7f");

    @Test
    void roundTripKeepsMicrosecondsForHistory() {
        Instant position = Instant.parse("2026-09-24T15:04:05.123456Z");
        ExpenseCursor cursor = new ExpenseCursor(ExpenseCursor.Mode.OCCURRED, position, ID);

        String encoded = cursor.encode();

        assertFalse(encoded.contains("|"), "el cursor debe ser opaco");
        assertFalse(encoded.contains("="), "sin relleno Base64");
        assertEquals(cursor, ExpenseCursor.decode(encoded, ExpenseCursor.Mode.OCCURRED));
    }

    @Test
    void roundTripForIncrementalSync() {
        ExpenseCursor cursor = new ExpenseCursor(ExpenseCursor.Mode.UPDATED, Instant.parse("2026-09-01T00:00:00Z"), ID);
        assertEquals(cursor, ExpenseCursor.decode(cursor.encode(), ExpenseCursor.Mode.UPDATED));
    }

    @Test
    void cursorOfOneModeIsRejectedInTheOther() {
        String history = new ExpenseCursor(ExpenseCursor.Mode.OCCURRED, Instant.now(), ID).encode();
        assertThrows(IllegalArgumentException.class, () -> ExpenseCursor.decode(history, ExpenseCursor.Mode.UPDATED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "no-es-base64!!", "YWJj"})
    void invalidCursorIsAClientError(String raw) {
        assertThrows(IllegalArgumentException.class, () -> ExpenseCursor.decode(raw, ExpenseCursor.Mode.OCCURRED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"o|2026-09-24T15:04:05Z", "o|ayer|" + "0b6f1e9c-3c1d-4d5e-9f10-2a3b4c5d6e7f",
            "o|2026-09-24T15:04:05Z|no-uuid", "x|2026-09-24T15:04:05Z|0b6f1e9c-3c1d-4d5e-9f10-2a3b4c5d6e7f",
            "o|2026-09-24T15:04:05Z|0b6f1e9c-3c1d-4d5e-9f10-2a3b4c5d6e7f|extra"})
    void wellFormedBase64WithBadContentIsRejected(String plain) {
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> ExpenseCursor.decode(raw, ExpenseCursor.Mode.OCCURRED));
    }

    @Test
    void nullCursorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ExpenseCursor.decode(null, ExpenseCursor.Mode.OCCURRED));
    }

    @Test
    void limitDefaultsToFifty() {
        assertEquals(50, ExpenseCursor.resolveLimit(null));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50, 200})
    void limitWithinRangeIsKept(int limit) {
        assertEquals(limit, ExpenseCursor.resolveLimit(limit));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 201, 1000})
    void limitOutOfRangeIsAClientError(int limit) {
        assertThrows(IllegalArgumentException.class, () -> ExpenseCursor.resolveLimit(limit));
    }
}
