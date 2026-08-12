package ec.paktay.business.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.DeviceResponse;
import ec.paktay.business.dto.UpsertDeviceRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {
    private final JdbcClient jdbc;

    public DeviceService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public DeviceResponse upsert(UUID userId, String displayName, String email, UpsertDeviceRequest request) {
        ensureProfile(userId, displayName, email);
        return jdbc.sql("""
                insert into user_devices (user_id, device_id, platform, device_name, biometric_enabled)
                values (:userId, :deviceId, :platform, :deviceName, :biometricEnabled)
                on conflict (user_id, device_id) do update set platform = excluded.platform,
                  device_name = excluded.device_name, biometric_enabled = excluded.biometric_enabled,
                  last_authenticated_at = now(), updated_at = now()
                returning id, device_id, platform, device_name, biometric_enabled, last_authenticated_at, created_at
                """).param("userId", userId).param("deviceId", request.deviceId()).param("platform", request.platform())
                .param("deviceName", request.deviceName()).param("biometricEnabled", request.biometricEnabled()).query(this::map).single();
    }

    public List<DeviceResponse> list(UUID userId) {
        return jdbc.sql("select id, device_id, platform, device_name, biometric_enabled, last_authenticated_at, created_at from user_devices where user_id = :userId order by last_authenticated_at desc")
                .param("userId", userId).query(this::map).list();
    }

    public boolean remove(UUID userId, UUID deviceId) {
        return jdbc.sql("delete from user_devices where user_id = :userId and device_id = :deviceId")
                .param("userId", userId).param("deviceId", deviceId).update() > 0;
    }

    private void ensureProfile(UUID userId, String displayName, String email) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("El token no incluye el correo del usuario");
        String name = displayName == null || displayName.isBlank() ? email : displayName;
        jdbc.sql("insert into profiles (id, display_name, email) values (:id, :name, :email) on conflict (id) do nothing")
                .param("id", userId).param("name", name).param("email", email).update();
        jdbc.sql("insert into user_settings (user_id) values (:id) on conflict (user_id) do nothing").param("id", userId).update();
    }

    private DeviceResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new DeviceResponse(rs.getObject("id", UUID.class), rs.getObject("device_id", UUID.class), rs.getString("platform"),
                rs.getString("device_name"), rs.getBoolean("biometric_enabled"), rs.getObject("last_authenticated_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
