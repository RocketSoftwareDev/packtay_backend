package ec.paktay.auth.dto;

import java.util.List;

/**
 * Cómo entra la cuenta: con contraseña y/o con proveedores ("apple", "google"). La app decide
 * con esto si ofrece «Cambiar contraseña» y si «Eliminar mi cuenta» pide contraseña o Face ID.
 */
public record AccountMethodsResponse(boolean hasPassword, List<String> providers) {
}
