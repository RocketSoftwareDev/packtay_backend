package ec.paktay.business.controller;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.Arrays;

import ec.paktay.business.dto.BankResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog/banks")
@Tag(name = "Catálogo · Bancos")
@SecurityRequirement(name = "bearerAuth")
public class BankController {
    private static final Logger log = LoggerFactory.getLogger(BankController.class);
    private final JdbcClient jdbc;

    public BankController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @Operation(summary = "Listar bancos disponibles", description = "Ruta autenticada. Devuelve únicamente entidades con oferta configurada e incluye supportedCardTypes y creditBrands para construir el formulario dinámico.")
    @ApiResponse(responseCode = "200", description = "Entidades, tipos admitidos y marcas de crédito disponibles")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public List<BankResponse> list(@AuthenticationPrincipal Jwt ignored) {
        log.debug("catalog_banks_query_started sql=select_id_name_from_banks_where_active");
        List<BankResponse> banks = jdbc.sql("""
                select b.id, b.name, b.logo_url,
                       array(select distinct o.card_type from bank_card_offerings o
                              where o.bank_id=b.id and o.active order by o.card_type) supported_types,
                       array(select distinct o.brand from bank_card_offerings o
                              where o.bank_id=b.id and o.active and o.card_type='CREDIT' order by o.brand) credit_brands
                  from banks b where b.active
                   and exists(select 1 from bank_card_offerings o where o.bank_id=b.id and o.active)
                 order by b.name
                """).query(this::map).list();
        log.info("catalog_banks_query_completed count={}", banks.size());
        return banks;
    }

    private BankResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new BankResponse(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("logo_url"),
                Arrays.asList((String[]) rs.getArray("supported_types").getArray()),
                Arrays.asList((String[]) rs.getArray("credit_brands").getArray()));
    }
}
