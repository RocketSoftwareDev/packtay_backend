package ec.paktay.business.dto;

public record CurrencyResponse(String code, String name, String symbol, int decimalPlaces, boolean base) {
}
