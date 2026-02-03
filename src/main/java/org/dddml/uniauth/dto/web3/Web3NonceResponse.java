package org.dddml.uniauth.dto.web3;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Web3NonceResponse {
    private String nonce;
    private String message;
    private long expiresIn;
}
