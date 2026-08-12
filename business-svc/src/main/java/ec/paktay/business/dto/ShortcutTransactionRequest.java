package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ShortcutTransactionRequest(
        @NotNull UUID idempotencyKey,
        @NotNull UUID bankId,
        @NotBlank @Pattern(regexp = "^[0-9]{4}$") String cardLast4,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
        @NotBlank @Size(max = 180) String merchantRaw,
        @NotNull OffsetDateTime occurredAt,
        @NotBlank @Size(max = 10000) String rawPayload,
        @Size(max = 2000) String rawText) {
}
