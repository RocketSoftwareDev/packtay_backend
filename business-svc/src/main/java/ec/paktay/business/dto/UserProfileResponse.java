package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserProfileResponse(UUID id, String email, String displayName, String avatarUrl,
                                  boolean isAutomatic, boolean isHaveCards,
                                  boolean isHaveCategory,
                                  OffsetDateTime avatarUpdatedAt) {
}
