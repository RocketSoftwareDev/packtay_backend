package ec.paktay.business.controller;

import ec.paktay.business.dto.CreateSystemCategoryRequest;
import ec.paktay.business.dto.SystemCategoryResponse;
import ec.paktay.business.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    public AdminCategoryController(CategoryService categories) { this.categories = categories; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear categoría predeterminada")
    @ApiResponse(responseCode = "201", description = "Categoría predeterminada creada")
    @ApiResponse(responseCode = "403", description = "Se requiere el rol ADMIN")
    public SystemCategoryResponse create(@Valid @RequestBody CreateSystemCategoryRequest request) {
        return categories.createSystemCategory(request.name(), request.displayOrder());
    }
}
