package ec.paktay.business.service;

import ec.paktay.business.dto.CreateUnregisteredPaymentRequest;
import ec.paktay.business.dto.UnregisteredPaymentResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnregisteredPaymentService {
    private final JdbcClient jdbc;

    public UnregisteredPaymentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UnregisteredPaymentResponse create(CreateUnregisteredPaymentRequest request) {
        return jdbc.sql("""
                insert into unregistered_payments (amount, merchant, card_name, device_id)
                values (:amount, :merchant, :cardName, :deviceId)
                returning id, amount, merchant, card_name, device_id, created_at
                """)
                .param("amount", request.amount())
                .param("merchant", request.merchant().trim())
                .param("cardName", request.cardName().trim())
                .param("deviceId", trimToNull(request.deviceId()), java.sql.Types.VARCHAR)
                .query((rs, rowNum) -> new UnregisteredPaymentResponse(
                        rs.getLong("id"), rs.getBigDecimal("amount"), rs.getString("merchant"),
                        rs.getString("card_name"), rs.getString("device_id"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)))
                .single();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
