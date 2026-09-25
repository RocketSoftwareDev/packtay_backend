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
        OffsetDateTime updatedAt,
        @Schema(description = "Gasto de Wallet más alto de este comercio (activo, en la moneda del gasto); null si no hay. "
                + "El teléfono no guarda solo un pago que pase 3 veces este monto o 500 USD.", nullable = true)
        java.math.BigDecimal maxAmount) {
}
