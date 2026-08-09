package org.dddml.uniauth.dto;

import java.util.List;

public record LoginMethodsResponse(
        List<LoginMethodDto> loginMethods,
        int count) {
}
