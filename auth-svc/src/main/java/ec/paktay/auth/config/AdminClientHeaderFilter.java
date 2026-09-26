package ec.paktay.auth.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Candados de /api/v1/admin/** (igual que en business-svc):
 * 1. Cabecera X-Paktay-Client. No es un secreto: la manda el servidor de la web y corta llamadas
 *    de otros sitios.
 * 2. El token tiene que ser del cliente del panel (azp = paktay-admin-panel). Un token de la app
 *    móvil no sirve aquí aunque la cuenta tenga ADMIN.
 * Corre después de Spring Security, así que el token ya está validado cuando se lee azp.
 */
@Component
public class AdminClientHeaderFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Paktay-Client";
    private static final Logger log = LoggerFactory.getLogger(AdminClientHeaderFilter.class);

    private final boolean required;
    private final String expected;
    private final boolean panelTokenRequired;
    private final String panelClientId;

    public AdminClientHeaderFilter(@Value("${paktay.admin.require-client-header:true}") boolean required,
                                   @Value("${paktay.admin.client-header-value:admin-web}") String expected,
                                   @Value("${paktay.admin.require-panel-token:true}") boolean panelTokenRequired,
                                   @Value("${paktay.admin.panel-client-id:paktay-admin-panel}") String panelClientId) {
        this.required = required;
        this.expected = expected;
        this.panelTokenRequired = panelTokenRequired;
        this.panelClientId = panelClientId;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || !request.getRequestURI().startsWith("/api/v1/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (required && !expected.equals(request.getHeader(HEADER))) {
            reject(request, response, "ADMIN_CLIENT_REQUIRED");
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (panelTokenRequired && authentication instanceof JwtAuthenticationToken token
                && !panelClientId.equals(token.getToken().getClaimAsString("azp"))) {
            reject(request, response, "ADMIN_TOKEN_REQUIRED");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String code) throws IOException {
        log.warn("admin_request_rejected requestId={} code={} path={}", MDC.get("requestId"), code, request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"Solicitud no permitida\",\"code\":\"" + code + "\"}");
    }
}
