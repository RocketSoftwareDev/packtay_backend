package ec.paktay.business.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record EntitlementsResponse(
        @Schema(allowableValues = {"FREE", "PRO", "DUO"}, description = "En la beta, sin suscripción, todos son PRO")
        String plan,
        @Schema(description = "Topes del plan; null = sin límite")
        Limits limits,
        @Schema(description = "Uso actual del mes (zona horaria del usuario)")
        Usage usage) {

    public record Limits(Integer cards, Integer capturesPerMonth, Integer budgetCategories, Integer historyMonths) { }

    public record Usage(int cards, int capturesThisMonth, int budgetCategories) { }
}
