package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SystemCategoryResponse(UUID id, String name, boolean active, short displayOrder, OffsetDateTime createdAt) {
}
