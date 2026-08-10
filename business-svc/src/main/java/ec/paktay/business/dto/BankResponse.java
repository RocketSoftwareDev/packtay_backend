package ec.paktay.business.dto;

import java.util.UUID;

public record BankResponse(UUID id, String slug, String name, String monogram, String brandColor, boolean inkOnLight) {
}
