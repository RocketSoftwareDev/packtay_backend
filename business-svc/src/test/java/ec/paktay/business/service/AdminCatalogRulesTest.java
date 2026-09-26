package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ec.paktay.business.controller.AdminCategoryControllerAccess;
import org.junit.jupiter.api.Test;

class AdminCatalogRulesTest {
    @Test
    void codigoDeSubcategoriaDesdeElNombre() {
        assertEquals("veterinario-y-peluqueria", AdminCategoryControllerAccess.slug("Veterinario y peluquería"));
        assertEquals("cafe-te", AdminCategoryControllerAccess.slug("  Café / Té  "));
    }

    @Test
    void nombreNormalizadoDeBanco() {
        assertEquals("BANCO DEL PACIFICO", AdminCatalogService.normalize("  Banco del Pacífico "));
    }

    @Test
    void clavesYEtiquetasDePlan() {
        assertEquals("PRO_BETA", AdminInsightsService.planKey("PRO", "BETA"));
        assertEquals("PRO_TESTER", AdminInsightsService.planKey("PRO", "TESTER"));
        assertEquals("PRO_STORE", AdminInsightsService.planKey("PRO", "APP_STORE"));
        assertEquals("FREE", AdminInsightsService.planKey("FREE", "TESTER"));
        assertEquals("DUO", AdminInsightsService.planKey("DUO", "PLAY_STORE"));
        assertEquals("PRO · Beta", AdminInsightsService.planLabel("PRO_BETA"));
        assertEquals("FREE", AdminInsightsService.planLabel("FREE"));
    }
}
