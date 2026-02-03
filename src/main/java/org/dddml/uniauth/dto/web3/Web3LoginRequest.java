package org.dddml.uniauth.dto.web3;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
    private String message;

    @NotBlank(message = "Signature is required")
    @Pattern(regexp = "^0x[a-fA-F0-9]{130}$", message = "Invalid signature format")
    private String signature;

    @NotBlank(message = "Nonce is required")
    private String nonce;

    private Integer chainId;
}
