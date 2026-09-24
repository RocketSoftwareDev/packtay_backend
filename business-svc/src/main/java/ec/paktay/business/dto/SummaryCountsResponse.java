package ec.paktay.business.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Conteos para estados vacíos de la pantalla de inicio. */
public record SummaryCountsResponse(
        @Schema(description = "Categorías activas del usuario")
        int activeCategories,
        @Schema(description = "Tarjetas ACTIVE del usuario")
        int activeCards) {
}
