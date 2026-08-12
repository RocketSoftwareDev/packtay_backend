package ec.paktay.business.controller;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.BankResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private final JdbcClient jdbc;

    public BankController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @Operation(summary = "Listar bancos disponibles", description = "Catálogo público autenticado de bancos activos para registrar tarjetas.")
    public List<BankResponse> list(@AuthenticationPrincipal Jwt ignored) {
        return jdbc.sql("select id, name from banks where active order by name").query(this::map).list();
    }

    private BankResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new BankResponse(rs.getObject("id", UUID.class), rs.getString("name"));
    }
}
