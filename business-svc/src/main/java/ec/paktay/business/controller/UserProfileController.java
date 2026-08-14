package ec.paktay.business.controller;

import java.io.IOException;
import java.util.UUID;

import ec.paktay.business.dto.UserProfileResponse;
import ec.paktay.business.dto.UpdateAutomaticRequest;
import ec.paktay.business.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/user/profile")
@Tag(name = "Usuario · Perfil", description = "Perfil del usuario autenticado y foto almacenada en Supabase Storage")
@SecurityRequirement(name = "bearerAuth")
public class UserProfileController {
    private final UserProfileService profiles;

    public UserProfileController(UserProfileService profiles) { this.profiles = profiles; }

    @GetMapping
    @Operation(summary = "Consultar mi perfil",
            description = "Devuelve el perfil, isHaveCards e isHaveCategory, calculados a partir de las tarjetas y categorías activas del usuario.")
    @ApiResponse(responseCode = "200", description = "Perfil actual")
    public UserProfileResponse get(@AuthenticationPrincipal Jwt jwt) {
        return profiles.get(userId(jwt), jwt.getClaimAsString("email"), displayName(jwt));
    }

    @PutMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Subir o reemplazar mi foto de perfil",
            description = "Acepta JPEG, PNG o WebP de hasta 5 MB. El backend guarda el objeto en Supabase y persiste su URL pública.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            schema = @Schema(type = "object")))
    @ApiResponse(responseCode = "200", description = "Foto almacenada y perfil actualizado")
    @ApiResponse(responseCode = "400", description = "Formato o tamaño inválido")
    public UserProfileResponse upload(@AuthenticationPrincipal Jwt jwt,
                                      @RequestPart("file") MultipartFile file) throws IOException {
        return profiles.upload(userId(jwt), jwt.getClaimAsString("email"), displayName(jwt),
                file.getContentType(), file.getBytes());
    }

    @DeleteMapping("/avatar")
    @Operation(summary = "Eliminar mi foto de perfil")
    @ApiResponse(responseCode = "200", description = "Foto eliminada y perfil actualizado")
    public UserProfileResponse remove(@AuthenticationPrincipal Jwt jwt) {
        return profiles.remove(userId(jwt), jwt.getClaimAsString("email"), displayName(jwt));
    }

    @PutMapping("/automatic")
    @Operation(summary = "Actualizar mi preferencia automática",
            description = "Activa o desactiva isAutomatic únicamente para el usuario identificado por el bearer token.")
    @ApiResponse(responseCode = "200", description = "Preferencia actualizada y perfil resultante")
    @ApiResponse(responseCode = "400", description = "isAutomatic es obligatorio")
    @ApiResponse(responseCode = "401", description = "Se requiere un JWT válido")
    public UserProfileResponse updateAutomatic(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @org.springframework.web.bind.annotation.RequestBody UpdateAutomaticRequest request) {
        return profiles.updateAutomatic(userId(jwt), jwt.getClaimAsString("email"), displayName(jwt),
                request.isAutomatic());
    }

    private UUID userId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    private String displayName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        return name == null || name.isBlank() ? jwt.getClaimAsString("preferred_username") : name;
    }
}
