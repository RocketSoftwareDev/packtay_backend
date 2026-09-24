package ec.paktay.business.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record MerchantRuleResponse(
        UUID id,
        @Schema(description = "Clave de la regla: el comercio hasta el primer bloque con números, máximo dos palabras (\"FYBECA 123 QUITO\" → \"FYBECA\")")
        String merchantKey,
        @Schema(description = "Último texto del comercio tal como llegó de Wallet")
        String merchantName,
        UUID categoryId,
        String categoryName,
        @Schema(description = "Veces que se asignó este comercio")
        int uses,
        OffsetDateTime lastUsedAt,
        OffsetDateTime updatedAt) {
}
