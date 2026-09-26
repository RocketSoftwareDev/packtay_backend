package ec.paktay.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class IdentityRulesTest {
    @Test
    void mismaNormalizacionQueBusinessSvc() {
        assertEquals("troll@gmail.com", EmailRules.normalize("T.Roll+1@googlemail.com"));
        assertEquals("ana.maria@outlook.com", EmailRules.normalize("Ana.Maria+x@outlook.com"));
    }

    @Test
    void idAnonimoSinCorreo() {
        assertEquals("id anónimo 7f3a…c21", EmailRules.anonymousId("7f3a1b2c-0000-4000-8000-000000000c21"));
    }

    @Test
    void valorDelBloqueoSegunTipo() {
        assertEquals("troll@gmail.com", IdentityBlockService.valueFor("EMAIL", "T.roll+2@gmail.com"));
        assertEquals("mailinator.com", IdentityBlockService.valueFor("DOMAIN", "alguien@Mailinator.com"));
        assertEquals("181.39.10.20", IdentityBlockService.valueFor("IP", " 181.39.10.20 "));
        assertThrows(IllegalArgumentException.class, () -> IdentityBlockService.valueFor("DOMAIN", "gmail.com"));
        assertThrows(IllegalArgumentException.class, () -> IdentityBlockService.valueFor("EMAIL", "no-es-correo"));
    }

    @Test
    void alcancesPermitidosSegunTipo() {
        List<String> all = List.of("TICKETS", "REGISTRATION", "ACCOUNT");
        assertEquals(all, IdentityBlockService.scopesFor("EMAIL", all));
        assertEquals(List.of("TICKETS", "REGISTRATION"), IdentityBlockService.scopesFor("DOMAIN", all));
        assertEquals(List.of("TICKETS"), IdentityBlockService.scopesFor("IP", all));
        assertEquals(List.of("TICKETS"), IdentityBlockService.scopesFor("EMAIL", List.of()));
    }
}
