package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record RecurringListResponse(
        @Schema(description = "Activos primero (por próximo cobro), luego pausados; los cancelados no se listan")
        List<RecurringPaymentResponse> items,
        @Schema(description = "Suma de monthlyEquivalent de los activos")
        BigDecimal monthlyTotal,
        int activeCount) {
}
