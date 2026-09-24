package ec.paktay.business.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record ExpensePageResponse(
        @Schema(description = "Gastos de la página, en el orden pedido")
        List<ExpenseResponse> items,
        @Schema(description = "Cursor opaco para pedir la página siguiente (parámetro cursor); null cuando no hay más filas",
                nullable = true)
        String nextCursor) {
}
