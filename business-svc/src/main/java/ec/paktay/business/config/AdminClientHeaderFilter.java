package ec.paktay.business.config;

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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cabecera obligatoria del panel en /api/v1/admin/**: X-Paktay-Client con el valor configurado.
 *
 * No es un secreto (está en el JavaScript de la web). Sirve porque una cabecera propia obliga
 * al navegador a pedir permiso (preflight de CORS), y ese permiso solo se da al dominio del
 * panel: así otra página no puede usar la sesión de un administrador (CSRF). La app móvil no
 * llama estas rutas y no se ve afectada. La autorización real sigue siendo el JWT con rol ADMIN.
 */
@Component
public class AdminClientHeaderFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Paktay-Client";
    private static final Logger log = LoggerFactory.getLogger(AdminClientHeaderFilter.class);

    private final boolean required;
    private final String expected;

    public AdminClientHeaderFilter(@Value("${paktay.admin.require-client-header:true}") boolean required,
                                   @Value("${paktay.admin.client-header-value:admin-web}") String expected) {
        this.required = required;
        this.expected = expected;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !required || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expected.equals(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("admin_client_header_missing requestId={} path={}", MDC.get("requestId"), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"Solicitud no permitida\",\"code\":\"ADMIN_CLIENT_REQUIRED\"}");
    }
}
