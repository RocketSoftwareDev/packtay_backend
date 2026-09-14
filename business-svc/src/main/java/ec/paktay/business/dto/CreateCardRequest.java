package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateCardRequest(
        @NotNull UUID bankId,
        @NotBlank @Pattern(regexp = "^(CREDIT|DEBIT)$") String cardType,
        @Pattern(regexp = "^(VISA|MASTERCARD|DINERS|DISCOVER|AMEX)$") String creditBrand,
        /**
         * Nombre de Wallet. Opcional desde v0.20: el móvil ya no lo pide al dar
         * de alta la tarjeta porque el usuario no sabe qué texto manda Wallet
         * hasta que llega el primer consumo. Se asocia después con
         * PATCH /api/v1/user/cards/{id}/wallet-name.
         */
        @Size(max = 80) String name,
        @Size(max = 80) String alias,
        @Pattern(regexp = "^[0-9]{4}$", message = "debe contener exactamente cuatro dígitos") String last4,
        @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "debe ser un color hexadecimal #RRGGBB") String colorDark,
        @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "debe ser un color hexadecimal #RRGGBB") String colorLight,
        @PositiveOrZero BigDecimal initialBudget,
        @Pattern(regexp = "^[A-Z]{3}$", message = "debe ser un código ISO de tres letras") String currencyCode) {
}
