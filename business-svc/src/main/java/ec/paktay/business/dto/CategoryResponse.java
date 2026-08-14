package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CategoryResponse(UUID id, UUID systemCategoryId, String code, String name, String baseName, String icon, String colorDark,
                               String colorLight, short sortOrder, String origin, boolean active,
                               OffsetDateTime createdAt) {
}
