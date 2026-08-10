package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CardResponse(UUID id, UUID bankId, String bankName, String brand, String kind,
                           String last4, String nickname, boolean active, OffsetDateTime createdAt) {
}
