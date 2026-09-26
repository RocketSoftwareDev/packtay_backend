package ec.paktay.business.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Formulario público de soporte: sin token, con límites por IP y correo.
                        .requestMatchers("/api/v1/public/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/user/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(roles())))
                .build();
    }

    /**
     * CORS solo aplica a navegadores (la app móvil no lo usa).
     * - Rutas públicas de soporte: los dominios de la web del formulario (PAKTAY_CORS_PUBLIC_ORIGIN_PATTERNS;
     *   vacío = los mismos del panel).
     * - Todo lo demás: los dominios del panel (PAKTAY_CORS_ALLOWED_ORIGIN_PATTERNS). En producción, solo
     *   el dominio HTTPS del panel.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${paktay.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}") String origins,
            @Value("${paktay.cors.public-origin-patterns:}") String publicOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        String forPublic = publicOrigins == null || publicOrigins.isBlank() ? origins : publicOrigins;
        source.registerCorsConfiguration("/api/v1/public/**", cors(forPublic, List.of("POST", "GET", "OPTIONS")));
        source.registerCorsConfiguration("/**", cors(origins, List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")));
        return source;
    }

    private static CorsConfiguration cors(String origins, List<String> methods) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(methods);
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin", AdminClientHeaderFilter.HEADER));
        config.setExposedHeaders(List.of("Location", "X-Request-Id"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        return config;
    }

    private Converter<Jwt, ? extends AbstractAuthenticationToken> roles() {
        return jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            Collection<?> roleValues = realmAccess == null ? List.of() : (Collection<?>) realmAccess.getOrDefault("roles", List.of());
            var authorities = roleValues.stream().map(String::valueOf).map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }
}
