package ec.paktay.business.dto;
import java.util.UUID;
public record ShortcutTransactionResponse(UUID captureId, UUID transactionId, UUID cardId, boolean cardCreated, boolean duplicate, boolean reviewRequired) { }
