package ec.paktay.business.dto;

import java.util.UUID;

public record ShortcutTransactionResponse(UUID pendingMovementId, UUID suggestedCardId,
                                          UUID suggestedCategoryId, boolean duplicate) {
}
