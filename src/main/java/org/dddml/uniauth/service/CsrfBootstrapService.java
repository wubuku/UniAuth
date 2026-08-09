package org.dddml.uniauth.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class CsrfBootstrapService {

    public static final String SESSION_ATTRIBUTE =
            CsrfBootstrapService.class.getName() + ".TOKEN";

    private final SecureRandom secureRandom = new SecureRandom();

    public String token(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object existing = session.getAttribute(SESSION_ATTRIBUTE);
        if (existing instanceof String token && !token.isBlank()) {
            return token;
        }
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
        session.setAttribute(SESSION_ATTRIBUTE, token);
        return token;
    }
}
