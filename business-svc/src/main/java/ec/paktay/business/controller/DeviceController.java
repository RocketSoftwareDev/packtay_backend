package ec.paktay.business.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ec.paktay.business.dto.DeviceResponse;
import ec.paktay.business.dto.PushTokenRequest;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import ec.paktay.business.dto.UpsertDeviceRequest;
import ec.paktay.business.service.BudgetAlertService;
import ec.paktay.business.service.DeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/security/devices")
@Tag(name = "Seguridad biométrica", description = "Registra dispositivos y la preferencia biométrica local. No recibe biometría.")
@SecurityRequirement(name = "bearerAuth")
public class DeviceController {
    private final DeviceService devices;
    private final BudgetAlertService budgetAlerts;

    public DeviceController(DeviceService devices, BudgetAlertService budgetAlerts) {
        this.devices = devices;
        this.budgetAlerts = budgetAlerts;
    }

    @PutMapping("/me")
    @Operation(summary = "Registrar o actualizar mi dispositivo", description = "Registra la preferencia de desbloqueo local con Face ID, Touch ID o BiometricPrompt. Nunca envíes biometría al servidor.")
    public DeviceResponse upsert(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpsertDeviceRequest request) {
        return devices.upsert(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("name"), jwt.getClaimAsString("email"), request);
    }

    @GetMapping
    @Operation(summary = "Listar mis dispositivos")
    public List<DeviceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return devices.list(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping("/{deviceId}/push-token")
    @Operation(summary = "Registrar o quitar el token de avisos (Firebase)", description = "Ruta autenticada. El dispositivo debe existir (PUT /me antes). "
            + "token null lo quita: al cerrar sesión o si el usuario apagó los avisos. Un token que tenía otra cuenta en el mismo teléfono se le quita a esa cuenta.")
    @ApiResponse(responseCode = "204", description = "Token guardado o quitado")
    @ApiResponse(responseCode = "400", description = "El dispositivo no existe o el token es demasiado largo")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pushToken(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId, @Valid @RequestBody PushTokenRequest request) {
        if (!devices.setPushToken(UUID.fromString(jwt.getSubject()), deviceId, request.token())) {
            throw new IllegalArgumentException("El dispositivo no existe");
        }
        if (request.token() != null && !request.token().isBlank()) {
            budgetAlerts.retryPending(UUID.fromString(jwt.getSubject()));
        }
    }

    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Eliminar un dispositivo", description = "Quita el dispositivo de la cuenta; la app debe borrar sus tokens locales.")
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
        if (!devices.remove(UUID.fromString(jwt.getSubject()), deviceId)) throw new IllegalArgumentException("El dispositivo no existe");
    }
}
