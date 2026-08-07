package org.dddml.uniauth.config;

import org.dddml.uniauth.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void missingStaticResourceReturnsNotFound() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                HttpMethod.GET.name(),
                "/oauth2/introspect-test"
        );

        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFoundException(
                new NoResourceFoundException(HttpMethod.GET, "oauth2/introspect-test"),
                new ServletWebRequest(request)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void generalExceptionDoesNotExposeItsMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                HttpMethod.GET.name(),
                "/api/example"
        );

        ResponseEntity<ErrorResponse> response = handler.handleGeneralException(
                new IllegalStateException("token=must-not-escape"),
                new ServletWebRequest(request)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).doesNotContain("must-not-escape");
    }
}
