package org.dddml.uniauth.config;

import org.dddml.uniauth.dto.ErrorResponse;
import org.dddml.uniauth.service.AuthRateLimiterUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

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

    @Test
    void rateLimiterUnavailableFailsClosedWithServiceUnavailable() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleRateLimiterUnavailable(
                        new AuthRateLimiterUnavailableException(
                                new IllegalStateException("database down")
                        )
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody())
                .containsEntry("error", "RATE_LIMITER_UNAVAILABLE");
        // The internal cause must never reach the response body.
        assertThat(response.getBody().toString()).doesNotContain("database down");
    }

    @Test
    void authenticationExceptionMapsToUnauthorized() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                HttpMethod.POST.name(),
                "/api/auth/login"
        );

        ResponseEntity<ErrorResponse> response = handler.handleAuthenticationException(
                new BadCredentialsException("secret-must-not-escape"),
                new ServletWebRequest(request)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode())
                .isEqualTo("AUTHENTICATION_FAILED");
        assertThat(response.getBody().getPath()).isEqualTo("/api/auth/login");
        assertThat(response.getBody().getDetail())
                .doesNotContain("secret-must-not-escape");
    }

    @Test
    void accessDeniedMapsToForbidden() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                HttpMethod.POST.name(),
                "/api/user/password"
        );

        ResponseEntity<ErrorResponse> response = handler.handleAccessDeniedException(
                new AccessDeniedException("denied"),
                new ServletWebRequest(request)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("ACCESS_DENIED");
        assertThat(response.getBody().getPath()).isEqualTo("/api/user/password");
    }

    @Test
    void validationUsesFieldMessageAndFallsBackWhenNoFieldErrorExists()
            throws Exception {
        MethodParameter parameter = new MethodParameter(
                Object.class.getMethod("toString"),
                -1
        );
        MockHttpServletRequest request = new MockHttpServletRequest(
                HttpMethod.POST.name(),
                "/api/auth/register"
        );

        BeanPropertyBindingResult withFieldError =
                new BeanPropertyBindingResult(new Object(), "target");
        withFieldError.addError(
                new FieldError("target", "email", "email is invalid")
        );
        ResponseEntity<ErrorResponse> fieldResponse =
                handler.handleMethodArgumentNotValidException(
                        new MethodArgumentNotValidException(
                                parameter,
                                withFieldError
                        ),
                        new ServletWebRequest(request)
                );

        BeanPropertyBindingResult withoutFieldError =
                new BeanPropertyBindingResult(new Object(), "target");
        ResponseEntity<ErrorResponse> fallbackResponse =
                handler.handleMethodArgumentNotValidException(
                        new MethodArgumentNotValidException(
                                parameter,
                                withoutFieldError
                        ),
                        new ServletWebRequest(request)
                );

        assertThat(fieldResponse.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldResponse.getBody()).isNotNull();
        assertThat(fieldResponse.getBody().getDetail())
                .isEqualTo("email is invalid");
        assertThat(fallbackResponse.getBody()).isNotNull();
        assertThat(fallbackResponse.getBody().getDetail())
                .isEqualTo("Validation failed");
    }
}
