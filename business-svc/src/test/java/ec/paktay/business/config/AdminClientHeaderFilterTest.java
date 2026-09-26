package ec.paktay.business.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AdminClientHeaderFilterTest {
    private final AdminClientHeaderFilter filter = new AdminClientHeaderFilter(true, "admin-web", true, "paktay-admin-panel");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tokenDelPanelConCabeceraPasa() throws Exception {
        authenticate("paktay-admin-panel");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(admin(true), new MockHttpServletResponse(), chain);
        assertTrue(chain.getRequest() != null);
    }

    @Test
    void tokenDeLaAppMovilNoEntraAlPanel() throws Exception {
        authenticate("paktay-mobile");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(admin(true), response, chain);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("ADMIN_TOKEN_REQUIRED"));
        assertNull(chain.getRequest());
    }

    @Test
    void sinCabeceraSeRechazaAntesDeMirarElToken() throws Exception {
        authenticate("paktay-admin-panel");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(admin(false), response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("ADMIN_CLIENT_REQUIRED"));
    }

    @Test
    void lasRutasDeLaAppNoSeTocan() throws Exception {
        authenticate("paktay-mobile");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/user/cards"), new MockHttpServletResponse(), chain);
        assertTrue(chain.getRequest() != null);
    }

    private static MockHttpServletRequest admin(boolean header) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        if (header) request.addHeader(AdminClientHeaderFilter.HEADER, "admin-web");
        return request;
    }

    private static void authenticate(String azp) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("u-1").claim("azp", azp).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }
}
