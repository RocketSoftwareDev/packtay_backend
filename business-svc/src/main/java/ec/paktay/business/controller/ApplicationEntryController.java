package ec.paktay.business.controller;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app")
public class ApplicationEntryController {
    @GetMapping("/entry")
    public Map<String, Object> entry(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> access = jwt.getClaimAsMap("realm_access");
        Object roles = access == null ? List.of() : access.getOrDefault("roles", List.of());
        return Map.of("userId", jwt.getSubject(), "email", jwt.getClaimAsString("email"), "roles", roles, "authenticated", true);
    }
}

