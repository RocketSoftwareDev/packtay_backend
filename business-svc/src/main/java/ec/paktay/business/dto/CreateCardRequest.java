package ec.paktay.business.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCardRequest(
        @NotNull UUID bankId,
        @NotBlank @Pattern(regexp = "visa|mastercard|amex|diners|other") String brand,
        @NotBlank @Pattern(regexp = "credit|debit") String kind,
        @NotBlank @Pattern(regexp = "^[0-9]{4}$") String last4,
        @Size(max = 80) String nickname) {
}
