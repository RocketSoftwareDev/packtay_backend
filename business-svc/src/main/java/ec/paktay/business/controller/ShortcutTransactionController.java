package ec.paktay.business.controller;
import java.util.UUID;
import ec.paktay.business.dto.*;
import ec.paktay.business.service.ShortcutTransactionService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/transactions/ingest") @Tag(name="Ingestión de consumos") @SecurityRequirement(name="bearerAuth")
public class ShortcutTransactionController {
 private final ShortcutTransactionService service; public ShortcutTransactionController(ShortcutTransactionService service){this.service=service;}
 @PostMapping("/shortcut") @Operation(summary="Registrar un consumo recibido desde Apple Shortcuts",description="Valida monto y datos, evita duplicados, crea la tarjeta solo si no existe y deja la transacción pendiente de revisión.")
 public ShortcutTransactionResponse ingest(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ShortcutTransactionRequest request){return service.ingest(UUID.fromString(jwt.getSubject()),jwt.getClaimAsString("name"),jwt.getClaimAsString("email"),request);}
}
