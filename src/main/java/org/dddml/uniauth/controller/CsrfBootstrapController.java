package org.dddml.uniauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.config.CsrfBootstrapProperties;
import org.dddml.uniauth.service.CsrfBootstrapService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class CsrfBootstrapController {

    private final CsrfBootstrapService csrfBootstrapService;
    private final CsrfBootstrapProperties properties;

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(
            HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
                "token",
                csrfBootstrapService.token(request),
                "headerName",
                properties.getHeaderName()
        ));
    }
}
