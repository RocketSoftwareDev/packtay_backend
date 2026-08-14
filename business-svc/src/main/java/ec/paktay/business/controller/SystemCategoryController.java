package ec.paktay.business.controller;

import java.util.List;

import ec.paktay.business.dto.SystemCategoryResponse;
import ec.paktay.business.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog/categories")
@Tag(name = "Catálogo · Categorías", description = "Categorías principales y subcategorías predeterminadas")
@SecurityRequirement(name = "bearerAuth")
public class SystemCategoryController {
    private final CategoryService categories;

    public SystemCategoryController(CategoryService categories) { this.categories = categories; }

    @GetMapping
    @Operation(summary = "Listar subcategorías del sistema", description = "Ruta autenticada. parentCode y parentName sirven únicamente para agrupar y buscar; al seleccionar se guarda el id de la subcategoría.")
    @ApiResponse(responseCode = "200", description = "Catálogo jerárquico disponible")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public List<SystemCategoryResponse> list() { return categories.listSystemCategories(); }
}
