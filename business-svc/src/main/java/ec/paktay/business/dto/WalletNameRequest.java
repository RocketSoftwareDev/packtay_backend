package ec.paktay.business.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * El nombre exacto con el que Wallet identifica una tarjeta en el atajo.
 *
 * Llega tal cual lo entrega el atajo, sin normalizar: el usuario no lo escribe,
 * sólo confirma a qué tarjeta suya pertenece. La comparación con otras tarjetas
 * sí ignora mayúsculas y espacios de los extremos, que es lo que hace el índice.
 */
public record WalletNameRequest(@NotBlank @Size(max = 80) String walletName) {
}
