package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CategoryResponse(UUID id, String name, String origin, boolean active, OffsetDateTime createdAt) {
}
