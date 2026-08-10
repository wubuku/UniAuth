package org.dddml.uniauth.dto.web3;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.dddml.uniauth.dto.UserDto;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Web3AuthResponse {
    private UserDto user;
    private String message;
    private Boolean authenticated;
    private String accessToken;
    private String tokenType;
    private Long accessTokenExpiresIn;
    private Long refreshTokenExpiresIn;
    private String walletAddress;
    private String userId;
    private Boolean isNewUser;
}
