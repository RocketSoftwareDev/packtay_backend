package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** reserved: la categoría «Sin categoría» (día 9); no se edita, elimina, presupuesta ni asigna. */
public record CategoryResponse(UUID id, UUID systemCategoryId, String code, String name, String baseName, String icon, String colorDark,
                               String colorLight, short sortOrder, String origin, boolean active,
                               OffsetDateTime createdAt, boolean reserved) {
}
