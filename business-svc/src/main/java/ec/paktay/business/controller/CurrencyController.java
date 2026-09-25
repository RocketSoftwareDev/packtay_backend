package ec.paktay.business.controller;

import java.util.List;

import ec.paktay.business.dto.CurrencyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog/currencies")
@Tag(name = "Catálogo · Monedas")
@SecurityRequirement(name = "bearerAuth")
public class CurrencyController {
    private final JdbcClient jdbc;

    public CurrencyController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @Operation(summary = "Listar monedas", description = "Ruta autenticada. Monedas activas de la tabla currencies, la moneda base primero. "
            + "Es la única lista de monedas: la usa el selector de moneda de la cuenta y la pregunta «¿En qué moneda fue?» de Por revisar. "
            + "Agregar una moneda es una migración nueva.")
    @ApiResponse(responseCode = "200", description = "Monedas activas")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public List<CurrencyResponse> list(@AuthenticationPrincipal Jwt ignored) {
        return jdbc.sql("""
                select code, name, symbol, decimal_places, is_base_currency
                  from currencies where active
                 order by is_base_currency desc, name
                """).query((rs, rowNum) -> new CurrencyResponse(rs.getString("code").trim(), rs.getString("name"),
                        rs.getString("symbol"), rs.getInt("decimal_places"), rs.getBoolean("is_base_currency"))).list();
    }
}
