package ec.paktay.business.dto;

import java.util.UUID;
import java.util.List;

public record BankResponse(UUID id, String name, String logoUrl, List<String> supportedCardTypes,
                           List<String> creditBrands) {
}
