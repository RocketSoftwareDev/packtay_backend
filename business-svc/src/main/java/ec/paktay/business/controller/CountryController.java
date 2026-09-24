package ec.paktay.business.controller;

import java.util.List;

import ec.paktay.business.dto.CountryResponse;
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
@RequestMapping("/api/v1/catalog/countries")
@Tag(name = "Catálogo · Países")
@SecurityRequirement(name = "bearerAuth")
public class CountryController {
    private final JdbcClient jdbc;

    public CountryController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @Operation(summary = "Listar países y su moneda", description = "Ruta autenticada. Países activos con su moneda. "
            + "El teléfono lo usa con el país que manda el atajo: si la moneda del país no es USD, la captura queda en Por revisar "
            + "hasta que el usuario escriba lo que cobró el banco en dólares.")
    @ApiResponse(responseCode = "200", description = "Países activos")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public List<CountryResponse> list(@AuthenticationPrincipal Jwt ignored) {
        return jdbc.sql("""
                select c.code, c.name, c.currency_code, cur.symbol, cur.name as currency_name
                  from countries c join currencies cur on cur.code = c.currency_code
                 where c.active and cur.active
                 order by c.name
                """).query((rs, rowNum) -> new CountryResponse(rs.getString("code"), rs.getString("name"),
                        rs.getString("currency_code"), rs.getString("currency_name"), rs.getString("symbol"))).list();
    }
}
