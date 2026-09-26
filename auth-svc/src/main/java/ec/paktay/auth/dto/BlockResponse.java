package ec.paktay.auth.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record BlockResponse(UUID id, String type, String value, List<String> scopes, String reason,
                            OffsetDateTime createdAt, String createdBy, boolean automatic) {
}
