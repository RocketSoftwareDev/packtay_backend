package ec.paktay.business.controller;

import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.admin.AdminActor;
import ec.paktay.business.dto.admin.AdminCatalog;
import ec.paktay.business.service.AdminCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/catalog")
@Tag(name = "Admin · Bancos, monedas y países", description = "Catálogos globales; requiere rol ADMIN. Los cambios quedan en Auditoría.")
@SecurityRequirement(name = "bearerAuth")
public class AdminCatalogController {
    private final AdminCatalogService catalog;

    public AdminCatalogController(AdminCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/banks")
    @Operation(summary = "Listar bancos", description = "origin SYSTEM (con sus ofertas de tarjeta) o CUSTOM (creados por usuarios, "
            + "agrupados por nombre con su número de usuarios; id = nombre normalizado).")
    @ApiResponse(responseCode = "200", description = "Bancos")
    @ApiResponse(responseCode = "403", description = "Se requiere el rol ADMIN")
    public List<AdminCatalog.Bank> banks(@RequestParam(defaultValue = "SYSTEM") String origin) {
        return catalog.banks(origin);
    }

    @GetMapping("/banks/counts")
    @Operation(summary = "Contar bancos", description = "Bancos del sistema y nombres distintos de bancos creados por usuarios.")
    public AdminCatalog.BankCounts bankCounts() {
        return catalog.bankCounts();
    }

    @PostMapping("/banks")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear banco del sistema", description = "La app solo lo muestra cuando tenga al menos una oferta de tarjeta.")
    @ApiResponse(responseCode = "201", description = "Banco creado")
    @ApiResponse(responseCode = "409", description = "Ya existe uno con ese nombre")
    public AdminCatalog.Bank createBank(@Valid @RequestBody AdminCatalog.CreateBankRequest request, @AuthenticationPrincipal Jwt jwt) {
        return catalog.createBank(request, AdminActor.from(jwt));
    }

    @PatchMapping("/banks/{id}")
    @Operation(summary = "Activar o desactivar banco del sistema")
    @ApiResponse(responseCode = "200", description = "Banco actualizado")
    @ApiResponse(responseCode = "404", description = "No existe o no es del sistema")
    public AdminCatalog.Bank setBankActive(@PathVariable UUID id, @Valid @RequestBody AdminCatalog.ToggleRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return catalog.setBankActive(id, request.active(), AdminActor.from(jwt));
    }

    @PostMapping("/banks/{id}/offerings")
    @Operation(summary = "Agregar oferta de tarjeta", description = "CREDIT necesita marca (VISA, MASTERCARD, DINERS, DISCOVER, AMEX); "
            + "DEBIT va sin marca. Se marca verificada hoy.")
    @ApiResponse(responseCode = "200", description = "Banco con la oferta nueva")
    @ApiResponse(responseCode = "400", description = "Crédito sin marca")
    @ApiResponse(responseCode = "409", description = "La oferta ya existe")
    public AdminCatalog.Bank addOffering(@PathVariable UUID id, @Valid @RequestBody AdminCatalog.CreateOfferingRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return catalog.addOffering(id, request, AdminActor.from(jwt));
    }

    @GetMapping("/currencies")
    @Operation(summary = "Listar monedas", description = "Activas e inactivas; base = moneda de conversión (USD).")
    public List<AdminCatalog.Currency> currencies() {
        return catalog.currencies();
    }

    @PostMapping("/currencies")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear moneda", description = "Código ISO 4217 de 3 letras y su código numérico.")
    @ApiResponse(responseCode = "201", description = "Moneda creada")
    @ApiResponse(responseCode = "409", description = "Ya existe")
    public AdminCatalog.Currency createCurrency(@Valid @RequestBody AdminCatalog.CreateCurrencyRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return catalog.createCurrency(request, AdminActor.from(jwt));
    }

    @PatchMapping("/currencies/{code}")
    @Operation(summary = "Activar o desactivar moneda", description = "La base no se desactiva, ni una que usa un país activo (409).")
    @ApiResponse(responseCode = "200", description = "Moneda actualizada")
    @ApiResponse(responseCode = "409", description = "Es la base o la usa un país activo")
    public AdminCatalog.Currency setCurrencyActive(@PathVariable String code, @Valid @RequestBody AdminCatalog.ToggleRequest request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return catalog.setCurrencyActive(code, request.active(), AdminActor.from(jwt));
    }

    @GetMapping("/countries")
    @Operation(summary = "Listar países", description = "Con su moneda y cuántos usuarios tienen ese país.")
    public List<AdminCatalog.Country> countries() {
        return catalog.countries();
    }

    @PostMapping("/countries")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear país", description = "Código ISO 3166-1 alfa-2; su moneda tiene que existir y estar activa.")
    @ApiResponse(responseCode = "201", description = "País creado")
    @ApiResponse(responseCode = "409", description = "Ya existe o su moneda está inactiva")
    public AdminCatalog.Country createCountry(@Valid @RequestBody AdminCatalog.CreateCountryRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        return catalog.createCountry(request, AdminActor.from(jwt));
    }

    @PatchMapping("/countries/{code}")
    @Operation(summary = "Activar o desactivar país", description = "No se activa si su moneda está inactiva (409).")
    @ApiResponse(responseCode = "200", description = "País actualizado")
    @ApiResponse(responseCode = "409", description = "Su moneda está inactiva")
    public AdminCatalog.Country setCountryActive(@PathVariable String code, @Valid @RequestBody AdminCatalog.ToggleRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return catalog.setCountryActive(code, request.active(), AdminActor.from(jwt));
    }
}
