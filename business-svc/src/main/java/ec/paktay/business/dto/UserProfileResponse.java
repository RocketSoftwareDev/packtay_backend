package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserProfileResponse(UUID id, String email, String displayName, String avatarUrl,
                                  boolean isHaveCards, boolean isHaveCategory,
                                  OffsetDateTime avatarUpdatedAt,
                                  @Schema(description = "Zona horaria IANA del usuario; define el mes de sus presupuestos y filtros", example = "America/Guayaquil")
                                  String timezone,
                                  @Schema(description = "País ISO 3166-1 alfa-2 del usuario", example = "EC")
                                  String countryCode) {
}
