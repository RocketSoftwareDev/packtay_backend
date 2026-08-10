package ec.paktay.business.controller;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.BankResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/banks")
public class BankController {
    private final JdbcClient jdbc;

    public BankController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<BankResponse> list(@AuthenticationPrincipal Jwt ignored) {
        return jdbc.sql("select id, slug, short_name, monogram, brand_color, brand_ink_on_light from banks where active order by short_name")
                .query(this::map).list();
    }

    private BankResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new BankResponse(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("short_name"),
                rs.getString("monogram"), rs.getString("brand_color"), rs.getBoolean("brand_ink_on_light"));
    }
}
