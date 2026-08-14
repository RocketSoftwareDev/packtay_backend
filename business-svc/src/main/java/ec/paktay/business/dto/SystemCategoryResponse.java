package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SystemCategoryResponse(UUID id, String code, String name, String parentCode, String parentName, String icon, String colorDark,
                                     String colorLight, short sortOrder, boolean active, OffsetDateTime createdAt) {
}
