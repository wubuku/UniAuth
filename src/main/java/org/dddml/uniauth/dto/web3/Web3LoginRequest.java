package org.dddml.uniauth.dto.web3;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Web3LoginRequest {

    @NotBlank(message = "Wallet address is required")
    @Pattern(regexp = "^0x[a-fA-F0-9]{40}$", message = "Invalid wallet address format")
    private String walletAddress;

    @NotBlank(message = "Message is required")
    @Size(max = 4096, message = "Message is too long")
    private String message;

    @NotBlank(message = "Signature is required")
    @Pattern(regexp = "^0x[a-fA-F0-9]{130}$", message = "Invalid signature format")
    private String signature;

    @NotBlank(message = "Challenge handle is required")
    @Pattern(
            regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            message = "Invalid challenge handle"
    )
    private String challengeHandle;

    @NotBlank(message = "Nonce is required")
    @Pattern(regexp = "^[A-Za-z0-9]{16,64}$", message = "Invalid nonce format")
    private String nonce;

    @NotNull(message = "Chain ID is required")
    @Positive(message = "Chain ID must be positive")
    private Integer chainId;
}
