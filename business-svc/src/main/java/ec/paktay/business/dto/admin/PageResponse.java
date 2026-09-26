package ec.paktay.business.dto.admin;

import java.util.List;

/** Página de resultados del panel: page empieza en 1. */
public record PageResponse<T>(List<T> items, long total, int page, int size) {
}
