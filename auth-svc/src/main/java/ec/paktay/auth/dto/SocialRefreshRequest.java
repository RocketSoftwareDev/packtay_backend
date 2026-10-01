package ec.paktay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SocialRefreshRequest(@NotBlank @Size(max = 8192) String refreshToken) {
}
