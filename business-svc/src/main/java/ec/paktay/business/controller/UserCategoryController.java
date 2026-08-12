package ec.paktay.business.controller;

import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.CategoryResponse;
import ec.paktay.business.dto.CreateUserCategoryRequest;
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
    @Operation(summary = "Listar mis categorías", description = "Incluye las predeterminadas de Paktay y las categorías propias del usuario.")
    public List<CategoryResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return categories.listUserCategories(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear una categoría personal")
    @ApiResponse(responseCode = "201", description = "Categoría creada")
    public CategoryResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateUserCategoryRequest request) {
        return categories.createUserCategory(UUID.fromString(jwt.getSubject()), request.name());
    }
}
