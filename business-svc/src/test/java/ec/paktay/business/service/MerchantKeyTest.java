package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MerchantKeyTest {

    @Test
    void cortaEnElPrimerBloqueConNumeros() {
        assertEquals("FYBECA", MerchantKey.ruleKey("FYBECA 123 QUITO"));
        assertEquals("FYBECA", MerchantKey.ruleKey("Fybeca 456 Guayaquil"));
        assertEquals("SUPERMAXI", MerchantKey.ruleKey("SUPERMAXI 042 QUITO"));
    }

    @Test
    void guardaComoMaximoDosPalabras() {
        assertEquals("PAYPAL SPOTIFYAB", MerchantKey.ruleKey("PAYPAL *SPOTIFYAB"));
        assertEquals("UBER TRIP", MerchantKey.ruleKey("UBER *TRIP HELP.UBER.COM"));
        assertEquals("JUAN VALDEZ", MerchantKey.ruleKey("JUAN VALDEZ BOGOTA"));
    }

    @Test
    void quitaTildesYSimbolos() {
        assertEquals("CAFE NANDU", MerchantKey.ruleKey("  Café   Ñandú  "));
    }

    @Test
    void siEmpiezaConNumerosUsaSusLetras() {
        assertEquals("ELEVEN", MerchantKey.ruleKey("7ELEVEN 1234"));
        assertEquals("123", MerchantKey.ruleKey("123 456"));
    }

    @Test
    void sinTextoNoHayClave() {
        assertNull(MerchantKey.ruleKey("  *** "));
        assertNull(MerchantKey.ruleKey(null));
    }
}
