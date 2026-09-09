package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ShortcutPaymentRequest(
        @NotNull UUID eventId,
        @NotBlank @Size(max = 40) String amount,
        @NotBlank @Size(max = 180) String merchant,
        @NotBlank @Size(max = 120) String cardName,
        @NotNull OffsetDateTime occurredAt) {
}
