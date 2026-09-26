package ec.paktay.business.dto.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** DTOs de los catálogos del panel: categorías, bancos, monedas y países. */
public final class AdminCatalog {
    private AdminCatalog() {
    }

    /** Activar o desactivar un elemento del catálogo. */
    public record ToggleRequest(@NotNull Boolean active) {
    }

    // ---------------------------------------------------------------- categorías

    /**
     * Grupo de categorías. En la base no hay fila para el grupo: es el parent_code y
     * parent_name de sus subcategorías; ícono y color salen de la primera subcategoría.
     */
    public record CategoryGroup(String code, String name, String icon, String color, int order, List<Subcategory> subcategories) {
    }

    /** usersCount: usuarios que tienen esa categoría activa (solo el conteo). */
    public record Subcategory(UUID id, String code, String name, String parentCode, String icon, String color,
                              int order, boolean active, long usersCount) {
    }

    // ---------------------------------------------------------------- bancos

    /** Banco. En los CUSTOM (creados por usuarios) id es el nombre normalizado y agrupa a todos sus usuarios. */
    public record Bank(String id, String name, String country, String origin, String logoUrl, boolean active,
                       List<Offering> offerings, long usersCount) {
    }

    /** brand es null en débito (regla de la base: solo el crédito lleva marca). */
    public record Offering(UUID id, String cardType, String brand, String sourceUrl, LocalDate verifiedAt, boolean active) {
    }

    public record BankCounts(long system, long custom) {
    }

    public record CreateBankRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String country) {
    }

    public record CreateOfferingRequest(
            @NotNull @Pattern(regexp = "CREDIT|DEBIT") String cardType,
            @Pattern(regexp = "VISA|MASTERCARD|DINERS|DISCOVER|AMEX") String brand,
            @Size(max = 500) String sourceUrl) {
    }

    // ---------------------------------------------------------------- monedas y países

    public record Currency(String code, String numericCode, String name, String symbol, int decimals, boolean base, boolean active) {
    }

    public record CreateCurrencyRequest(
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String code,
            @NotBlank @Pattern(regexp = "^[0-9]{3}$") String numericCode,
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 8) String symbol,
            @NotNull Integer decimals) {
    }

    public record Country(String code, String name, String currencyCode, boolean active, long usersCount) {
    }

    public record CreateCountryRequest(
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String code,
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {
    }
}
