package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DeviceResponse(UUID id, UUID deviceId, String platform, String deviceName,
                             boolean biometricEnabled, OffsetDateTime lastAuthenticatedAt,
                             OffsetDateTime createdAt) {
}
