package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class BudgetAlertServiceTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void umbralPorPorcentaje() {
        assertNull(BudgetAlertService.threshold(d("71.99"), d("80")));
        assertEquals(90, BudgetAlertService.threshold(d("72"), d("80")));
        assertEquals(90, BudgetAlertService.threshold(d("79.99"), d("80")));
        assertEquals(100, BudgetAlertService.threshold(d("80"), d("80")));
        assertEquals(100, BudgetAlertService.threshold(d("312.40"), d("300")));
    }

    @Test
    void sinLimiteNoHayAviso() {
        assertNull(BudgetAlertService.threshold(d("50"), null));
        assertNull(BudgetAlertService.threshold(d("50"), BigDecimal.ZERO));
        assertNull(BudgetAlertService.threshold(null, d("80")));
    }

    @Test
    void retryPendienteUsaElUmbralActualYNoEnviaUnAvisoObsoleto() {
        assertEquals(100, BudgetAlertService.retryThreshold(List.of(90), 100));
        assertEquals(90, BudgetAlertService.retryThreshold(List.of(90), 90));
        assertNull(BudgetAlertService.retryThreshold(List.of(100), 90));
        assertNull(BudgetAlertService.retryThreshold(List.of(), 100));
    }

    @Test
    void montoComoLoEscribeLaApp() {
        assertEquals("$72,00", BudgetAlertService.money(d("72")));
        assertEquals("$1.234,50", BudgetAlertService.money(d("1234.5")));
    }
}
