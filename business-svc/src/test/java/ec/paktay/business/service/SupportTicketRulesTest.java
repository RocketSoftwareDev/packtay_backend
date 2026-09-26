package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SupportTicketRulesTest {
    @Test
    void etiquetaDelPlanDeLaCuenta() {
        assertEquals("PRO · Beta", SupportTicketService.planLabel("PRO", null));
        assertEquals("FREE · Tester", SupportTicketService.planLabel("FREE", "TESTER"));
        assertEquals("PRO · App Store", SupportTicketService.planLabel("PRO", "APP_STORE"));
    }

    @Test
    void elTokenSeGuardaComoHashDe64Caracteres() {
        String token = SupportTicketService.newToken();
        String hash = SupportTicketService.sha256(token);
        assertEquals(64, hash.length());
        assertEquals(hash, SupportTicketService.sha256(token));
        assertNotEquals(token, hash);
        assertNotEquals(SupportTicketService.newToken(), token);
    }

    @Test
    void laIpSaleDeCloudflareYNoDeXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "6.6.6.6");
        assertEquals("127.0.0.1", ClientIp.of(request));
        request.addHeader("CF-Connecting-IP", "181.39.10.20");
        assertEquals("181.39.10.20", ClientIp.of(request));
    }
}
