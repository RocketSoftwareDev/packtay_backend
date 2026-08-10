package ec.paktay.business.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpsertDeviceRequest(
        @NotNull UUID deviceId,
        @NotBlank @Pattern(regexp = "ios|android") String platform,
        @NotBlank @Size(max = 100) String deviceName,
        boolean biometricEnabled) {
}
