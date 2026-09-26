package ec.paktay.business.dto.support;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Contadores de la bandeja. "new" es palabra reservada en Java, de ahí el nombre del campo. */
public record SupportCounts(
        @JsonProperty("new") long fresh,
        long inProgress,
        long resolved,
        long pendingVerification,
        long blocks) {
}
