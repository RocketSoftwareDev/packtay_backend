package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import jakarta.validation.constraints.*;

public record ShortcutTransactionRequest(
        @NotNull UUID bankId, @NotBlank @Size(max = 120) String sourceLabel, @Size(max = 200) String title,
        @NotBlank @Size(max = 2000) String body, @NotNull OffsetDateTime postedAt,
        @NotNull @Positive long amountCents, @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotBlank @Size(max = 200) String merchantRaw, @NotBlank @Pattern(regexp = "^[0-9]{4}$") String cardLast4,
        @NotBlank @Pattern(regexp = "visa|mastercard|amex|diners|other") String cardBrand,
        @NotBlank @Pattern(regexp = "credit|debit") String cardKind) { }
