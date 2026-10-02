package ec.paktay.business.controller;

import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.CategoryResponse;
import ec.paktay.business.dto.CreateUserCategoryRequest;
import ec.paktay.business.dto.InactiveCategoryResponse;
import ec.paktay.business.dto.UpdateCategoryRequest;
import ec.paktay.business.dto.UpdateCategoryAppearanceRequest;
import ec.paktay.business.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/categories")
@Tag(name = "Usuario · Categorías", description = "Categorías disponibles para el usuario autenticado")
@SecurityRequirement(name = "bearerAuth")
public class UserCategoryController {
    private final CategoryService categories;

    public UserCategoryController(CategoryService categories) { this.categories = categories; }

    @GetMapping
    @Operation(summary = "Listar mis categorías activas", description = "Incluye las predeterminadas clonadas y las categorías propias activas, con icono Lucide, colores y orden personalizados. "
            + "Al final va la reservada «Sin categoría» (reserved = true) solo si tiene gastos: no se edita, elimina, presupuesta ni asigna a gastos nuevos.")
    public List<CategoryResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return categories.listUserCategories(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/inactive")
    @Operation(summary = "Listar mis categorías desactivadas",
            description = "Ruta autenticada. Cada una dice si se puede eliminar (sin gastos activos en los últimos 3 meses y sin pagos "
                    + "recurrentes activos o pausados que la usen), desde qué día si todavía no, qué recurrentes la usan y cuántos gastos "
                    + "pasarían a «Sin categoría».")
    @ApiResponse(responseCode = "200", description = "Categorías desactivadas")
    public List<InactiveCategoryResponse> inactive(@AuthenticationPrincipal Jwt jwt) {
        return categories.listInactiveCategories(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/{categoryId}/reactivate")
    @Operation(summary = "Reactivar una categoría desactivada",
            description = "Ruta autenticada. Vuelve con sus gastos, nombre y colores. No vuelve sola al presupuesto: se marca de nuevo en él.")
    @ApiResponse(responseCode = "200", description = "Categoría reactivada")
    @ApiResponse(responseCode = "404", description = "Categoría inexistente, ajena o reservada")
    public CategoryResponse reactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID categoryId) {
        return categories.reactivateUserCategory(UUID.fromString(jwt.getSubject()), categoryId);
    }

    @DeleteMapping("/{categoryId}/permanent")
    @Operation(summary = "Eliminar para siempre una categoría desactivada",
            description = "Ruta autenticada. Solo si está desactivada, sin gastos activos en los últimos 3 meses y sin pagos recurrentes "
                    + "activos o pausados que la usen. Sus gastos (de cualquier fecha) pasan a «Sin categoría», también los de meses "
                    + "cerrados; se borran sus reglas de comercios y presupuestos. No se puede deshacer.")
    @ApiResponse(responseCode = "200", description = "Eliminada; movedExpenses = gastos que pasaron a «Sin categoría»")
    @ApiResponse(responseCode = "404", description = "Categoría inexistente, ajena o reservada")
    @ApiResponse(responseCode = "409", description = "Está activa, tiene gastos en los últimos 3 meses o la usa un pago recurrente")
    public java.util.Map<String, Integer> deletePermanently(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID categoryId) {
        return java.util.Map.of("movedExpenses", categories.deleteUserCategory(UUID.fromString(jwt.getSubject()), categoryId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear una categoría personal")
    @ApiResponse(responseCode = "201", description = "Categoría creada")
    public CategoryResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateUserCategoryRequest request) {
        return categories.createUserCategory(UUID.fromString(jwt.getSubject()), request);
    }

    @PostMapping("/from-system/{systemCategoryId}")
    @Operation(summary = "Agregar una categoría predeterminada a mi catálogo",
            description = "Crea o reactiva la copia personal de una categoría global y devuelve su UUID de usuario.")
    @ApiResponse(responseCode = "200", description = "Categoría agregada o reactivada en el catálogo del usuario")
    @ApiResponse(responseCode = "400", description = "Categoría predeterminada inexistente o inactiva")
    public CategoryResponse addFromSystem(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable UUID systemCategoryId) {
        return categories.addSystemCategory(UUID.fromString(jwt.getSubject()), systemCategoryId);
    }

    @PutMapping("/{categoryId}")
    @Operation(summary = "Editar una categoría de mi catálogo",
            description = "Permite editar únicamente una categoría perteneciente al usuario autenticado.")
    @ApiResponse(responseCode = "200", description = "Categoría actualizada")
    @ApiResponse(responseCode = "400", description = "Categoría inexistente, ajena o datos duplicados")
    public CategoryResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID categoryId,
                                   @Valid @RequestBody UpdateCategoryRequest request) {
        return categories.updateUserCategory(UUID.fromString(jwt.getSubject()), categoryId, request);
    }

    @PatchMapping("/{categoryId}/appearance")
    @Operation(summary = "Editar alias, icono, colores y estado de mi categoría",
            description = "Ruta autenticada. Conserva la identidad y el nombre base; actualiza únicamente la presentación personal persistida.")
    @ApiResponse(responseCode = "200", description = "Presentación de categoría actualizada")
    @ApiResponse(responseCode = "400", description = "Categoría ajena, inexistente o alias duplicado")
    public CategoryResponse updateAppearance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID categoryId,
                                             @Valid @RequestBody UpdateCategoryAppearanceRequest request) {
        return categories.updateAppearance(UUID.fromString(jwt.getSubject()), categoryId, request);
    }

    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desactivar una categoría de mi catálogo",
            description = "Ruta autenticada. Desde el día 9 siempre la desactiva (antes borraba las que no tenían gastos): conserva sus gastos, "
                    + "la saca del presupuesto y se puede reactivar. Para borrarla: DELETE /{categoryId}/permanent.")
    @ApiResponse(responseCode = "204", description = "Categoría desactivada")
    @ApiResponse(responseCode = "400", description = "Categoría inexistente, ajena, reservada o ya inactiva")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID categoryId) {
        categories.deactivateUserCategory(UUID.fromString(jwt.getSubject()), categoryId);
    }
}
