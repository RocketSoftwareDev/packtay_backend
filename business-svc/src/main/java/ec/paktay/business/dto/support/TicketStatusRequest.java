package ec.paktay.business.dto.support;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TicketStatusRequest(@NotNull @Pattern(regexp = "NEW|IN_PROGRESS|RESOLVED") String status) {
}
