package ec.paktay.business.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record PushTokenRequest(
        @Schema(description = "Token de Firebase Cloud Messaging; null lo quita", nullable = true)
        @Size(max = 4096) String token) {
}
