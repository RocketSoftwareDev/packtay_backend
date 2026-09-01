package ec.paktay.business.dto;

import java.time.OffsetDateTime;

public record ShortcutCredentialResponse(
        boolean connected,
        String token,
        String tokenHint,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt) {
}
