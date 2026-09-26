package ec.paktay.business.controller;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ec.paktay.business.dto.CreateSystemCategoryRequest;
import ec.paktay.business.dto.SystemCategoryResponse;
import ec.paktay.business.dto.admin.AdminActor;
import ec.paktay.business.dto.admin.AdminCatalog;
import ec.paktay.business.exception.NotFoundException;
import ec.paktay.business.service.AdminAuditService;
import ec.paktay.business.service.AdminCatalogService;
import ec.paktay.business.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/catalog/categories")
@Tag(name = "Admin · Categorías predeterminadas", description = "Solo Admin mantiene el catálogo global; no accede a categorías ni datos financieros de usuarios.")
@SecurityRequirement(name = "bearerAuth")
public class AdminCategoryController {
    private final CategoryService categories;
    private final AdminCatalogService catalog;
    private final AdminAuditService audit;

    public AdminCategoryController(CategoryService categories, AdminCatalogService catalog, AdminAuditService audit) {
        this.categories = categories;
        this.catalog = catalog;
        this.audit = audit;
    }

    /** Nueva subcategoría: solo el grupo y el nombre; código, ícono, colores y orden los pone el servidor. */
    public record CreateSubcategoryRequest(@NotBlank @Size(max = 50) String parentCode, @NotBlank @Size(max = 80) String name) {
    }

    @GetMapping
    @Operation(summary = "Listar categorías por grupo", description = "Todas las subcategorías (activas e inactivas) agrupadas por parent_code, "
            + "con cuántos usuarios tienen cada una.")
    @ApiResponse(responseCode = "200", description = "Grupos con sus subcategorías")
    @ApiResponse(responseCode = "403", description = "Se requiere el rol ADMIN")
    public List<AdminCatalog.CategoryGroup> list() {
        return catalog.categories();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear subcategoría predeterminada", description = "Toma el ícono y los colores del grupo, arma el código a partir del nombre "
            + "y la pone al final del orden. Queda disponible para los usuarios; sólo se materializa en user_categories cuando cada usuario la agrega.")
    @ApiResponse(responseCode = "201", description = "Categoría predeterminada creada")
    @ApiResponse(responseCode = "400", description = "Ya existe una con ese nombre o código")
    @ApiResponse(responseCode = "404", description = "El grupo no existe")
    public SystemCategoryResponse create(@Valid @RequestBody CreateSubcategoryRequest request, @AuthenticationPrincipal Jwt jwt) {
        AdminCatalog.CategoryGroup group = catalog.categories().stream()
                .filter(item -> item.code().equals(request.parentCode())).findFirst()
                .orElseThrow(() -> new NotFoundException("El grupo no existe"));
        AdminCatalog.Subcategory model = group.subcategories().get(0);
        String name = request.name().trim();
        SystemCategoryResponse created = categories.createSystemCategory(new CreateSystemCategoryRequest(
                slug(name), name, group.code(), group.name(), model.icon(), darkColorOf(group), model.color(),
                (short) catalog.nextCategoryOrder()));
        audit.adminAction(AdminActor.from(jwt), "Subcategoría creada", null, group.name() + " › " + name, null,
                Map.of("code", created.code(), "parent", group.code()));
        return created;
    }

    @PatchMapping("/{code}")
    @Operation(summary = "Activar o desactivar subcategoría", description = "Desactivada deja de ofrecerse a usuarios nuevos; los gastos ya clasificados se conservan.")
    @ApiResponse(responseCode = "200", description = "Subcategoría actualizada")
    @ApiResponse(responseCode = "404", description = "No existe")
    public AdminCatalog.Subcategory setActive(@PathVariable String code, @Valid @RequestBody AdminCatalog.ToggleRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        return catalog.setCategoryActive(code, request.active(), AdminActor.from(jwt));
    }

    /** El color oscuro no viaja en el listado: se reutiliza el de la primera subcategoría del grupo. */
    private String darkColorOf(AdminCatalog.CategoryGroup group) {
        return catalog.darkColor(group.subcategories().get(0).code());
    }

    /** "Veterinario y peluquería" → "veterinario-y-peluqueria" (formato de system_categories.code). */
    static String slug(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        String slug = plain.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.length() > 50 ? slug.substring(0, 50).replaceAll("-$", "") : slug;
    }
}
