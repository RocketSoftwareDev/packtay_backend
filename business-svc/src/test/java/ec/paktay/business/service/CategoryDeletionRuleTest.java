package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class CategoryDeletionRuleTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

    @Test
    void sinGastosSePuedeEliminar() {
        CategoryDeletionRule.Verdict v = CategoryDeletionRule.evaluate(null, List.of(), TODAY);
        assertTrue(v.deletable());
        assertNull(v.deletableFrom());
    }

    @Test
    void conGastosHaceMasDeTresMesesSePuede() {
        assertTrue(CategoryDeletionRule.evaluate(LocalDate.of(2026, 7, 1), List.of(), TODAY).deletable());
        // Justo el día en que se cumplen 3 meses.
        assertTrue(CategoryDeletionRule.evaluate(LocalDate.of(2026, 7, 2), List.of(), TODAY).deletable());
    }

    @Test
    void conUnGastoRecienteDiceDesdeCuando() {
        CategoryDeletionRule.Verdict v = CategoryDeletionRule.evaluate(LocalDate.of(2026, 9, 12), List.of(), TODAY);
        assertFalse(v.deletable());
        assertEquals(LocalDate.of(2026, 12, 12), v.deletableFrom());
    }

    @Test
    void unRecurrenteLaBloqueaAunqueEsteQuieta() {
        CategoryDeletionRule.Verdict v = CategoryDeletionRule.evaluate(null, List.of("Netflix"), TODAY);
        assertFalse(v.deletable());
        assertNull(v.deletableFrom());
        assertEquals(List.of("Netflix"), v.blockingRecurring());
    }

    @Test
    void losDosMotivosALaVez() {
        CategoryDeletionRule.Verdict v = CategoryDeletionRule.evaluate(LocalDate.of(2026, 9, 30), List.of("Spotify"), TODAY);
        assertFalse(v.deletable());
        assertEquals(LocalDate.of(2026, 12, 30), v.deletableFrom());
        assertEquals(1, v.blockingRecurring().size());
    }
}
