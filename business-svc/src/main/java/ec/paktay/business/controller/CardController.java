package ec.paktay.business.controller;

import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.CardLimitRequest;
import ec.paktay.business.dto.CardResponse;
import ec.paktay.business.dto.CreateCardRequest;
import ec.paktay.business.dto.UpdateCardRequest;
import ec.paktay.business.dto.WalletNameRequest;
import ec.paktay.business.service.CardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/cards")
@Tag(name = "Usuario · Tarjetas", description = "Tarjetas y presupuesto inicial del usuario autenticado")
@SecurityRequirement(name = "bearerAuth")
public class CardController {
    private final CardService cards;

    public CardController(CardService cards) { this.cards = cards; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar una tarjeta", description = "Ruta autenticada. El alias es el apodo del usuario y puede repetirse. El nombre de Wallet es OPCIONAL desde v0.20: la app ya no lo pide porque el usuario no sabe qué texto manda Wallet hasta que llega el primer consumo; se asocia después con PATCH /{cardId}/wallet-name. Si se envía, no puede estar asociado a otra tarjeta activa del usuario. CREDIT exige creditBrand permitido; DEBIT prohíbe marca.")
    @ApiResponse(responseCode = "201", description = "Tarjeta creada")
    @ApiResponse(responseCode = "400", description = "Banco, moneda o tarjeta inválidos, o nombre de Wallet ya asociado a otra tarjeta activa")
    @ApiResponse(responseCode = "409", description = "Plan Free con 2 tarjetas registradas (activas o desactivadas): hay que pasar a Pro o eliminar una")
    public CardResponse register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCardRequest request) {
        return cards.register(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping
    @Operation(summary = "Listar mis tarjetas", description = "Devuelve las tarjetas del usuario autenticado (ACTIVE primero, luego INACTIVE) y su presupuesto del mes actual, calculado en la zona horaria del perfil. Las tarjetas eliminadas (DELETED) no aparecen. El campo status permite al móvil ocultar las desactivadas de los selectores.")
    @ApiResponse(responseCode = "200", description = "Tarjetas con tipo, marca de crédito, últimos cuatro, colores y estado persistidos")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public List<CardResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return cards.list(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping("/{cardId}")
    @Operation(summary = "Editar alias y colores de una tarjeta", description = "Ruta autenticada. Actualiza el alias visual opcional y los colores. El nombre identificador, banco, tipo, franquicia y últimos cuatro permanecen inmutables.")
    @ApiResponse(responseCode = "200", description = "Tarjeta actualizada")
    @ApiResponse(responseCode = "400", description = "Alias o colores inválidos")
    public CardResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId,
                               @Valid @RequestBody UpdateCardRequest request) {
        return cards.update(UUID.fromString(jwt.getSubject()), cardId, request);
    }

    @PutMapping("/{cardId}/limit")
    @Operation(summary = "Poner, cambiar o quitar mi límite mensual de una tarjeta", description = "Ruta autenticada. Es un tope que se pone el usuario, no el cupo del banco. "
            + "Rige desde el mes actual (zona horaria del perfil) y se repite cada mes. amount null quita el límite desde este mes y no vuelve el mes siguiente. "
            + "Sólo tarjetas activas.")
    @ApiResponse(responseCode = "200", description = "Tarjeta con su límite del mes en currentPeriodBudget")
    @ApiResponse(responseCode = "400", description = "La tarjeta no existe, no está activa o el monto no es positivo")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public CardResponse setLimit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId,
                                 @Valid @RequestBody CardLimitRequest request) {
        return cards.setLimit(UUID.fromString(jwt.getSubject()), cardId, request.amount());
    }

    @PatchMapping("/{cardId}/deactivate")
    @Operation(summary = "Desactivar una tarjeta", description = "Ruta autenticada. Pasa la tarjeta a INACTIVE: conserva su historial, deja de aceptar gastos nuevos, desaparece de los selectores del móvil y libera su nombre dentro del banco. Sólo se permite si la tarjeta no registró consumos en los últimos 3 meses.")
    @ApiResponse(responseCode = "200", description = "Tarjeta desactivada")
    @ApiResponse(responseCode = "400", description = "La tarjeta no existe, fue eliminada, ya estaba desactivada o tiene consumos en los últimos 3 meses")
    public CardResponse deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId) {
        return cards.deactivate(UUID.fromString(jwt.getSubject()), cardId);
    }

    @PatchMapping("/{cardId}/activate")
    @Operation(summary = "Reactivar una tarjeta desactivada", description = "Ruta autenticada. Devuelve una tarjeta INACTIVE a ACTIVE. Falla si otra tarjeta activa del usuario tiene el mismo nombre de Wallet. Una tarjeta eliminada (DELETED) no se puede reactivar.")
    @ApiResponse(responseCode = "200", description = "Tarjeta activa")
    @ApiResponse(responseCode = "400", description = "La tarjeta no existe, fue eliminada (\"La tarjeta fue eliminada\"), ya estaba activa o su nombre de Wallet ya lo usa otra tarjeta activa")
    public CardResponse activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId) {
        return cards.activate(UUID.fromString(jwt.getSubject()), cardId);
    }

    @PatchMapping("/{cardId}/wallet-name")
    @Operation(summary = "Asociar el nombre con el que Wallet identifica la tarjeta", description = "Ruta autenticada. Es la única forma de escribir el nombre de Wallet: el alta ya no lo pide y PUT /{cardId} no lo toca. El usuario no escribe este texto, sólo confirma a qué tarjeta suya pertenece el que llegó por el atajo. El nombre es único por usuario entre tarjetas ACTIVAS, no por banco: el atajo busca por nombre y no sabe de qué banco viene el consumo. Reasignar está permitido; quitárselo a otra tarjeta no, hay que borrarlo de esa primero.")
    @ApiResponse(responseCode = "200", description = "Nombre asociado")
    @ApiResponse(responseCode = "400", description = "La tarjeta no existe, está desactivada, el nombre viene vacío o ya lo tiene otra tarjeta activa")
    public CardResponse associateWalletName(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId,
                                            @Valid @RequestBody WalletNameRequest request) {
        return cards.associateWalletName(UUID.fromString(jwt.getSubject()), cardId, request.walletName());
    }

    @DeleteMapping("/{cardId}/wallet-name")
    @Operation(summary = "Quitar el nombre de Wallet de una tarjeta", description = "Ruta autenticada. Deja la tarjeta sin nombre asociado. A partir de ahí, los consumos que lleguen con ese nombre vuelven a la cola sin asignar. Hace falta para reasignar un nombre de una tarjeta a otra.")
    @ApiResponse(responseCode = "200", description = "Nombre retirado")
    @ApiResponse(responseCode = "400", description = "La tarjeta no existe")
    public CardResponse clearWalletName(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId) {
        return cards.clearWalletName(UUID.fromString(jwt.getSubject()), cardId);
    }

    @DeleteMapping("/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Eliminar una tarjeta (borrado lógico)", description = "Ruta autenticada. No borra la fila: la tarjeta pasa a DELETED, desaparece de GET /user/cards, libera su nombre de Wallet y no se puede reactivar. Sus gastos se conservan y siguen contando en presupuestos e historial (ExpenseResponse.cardStatus = DELETED). Se borran sus presupuestos por tarjeta. Vale desde ACTIVE o INACTIVE y exige la misma condición que desactivar: ningún consumo en los últimos 3 meses.")
    @ApiResponse(responseCode = "204", description = "Tarjeta eliminada")
    @ApiResponse(responseCode = "400", description = "La tarjeta no existe, ya fue eliminada o tiene consumos en los últimos 3 meses")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId) {
        cards.delete(UUID.fromString(jwt.getSubject()), cardId);
    }
}
