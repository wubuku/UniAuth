package org.dddml.email.controller;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
@Slf4j
public class EmailControllerAdvice {

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<Map<String, Object>> invalidRequest(Exception exception) {
        log.warn("Rejected invalid email service request: {}", exception.getClass().getSimpleName());
        return errorResponse(HttpStatus.BAD_REQUEST, "Invalid request");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException exception) {
        log.warn("Email service resource was not found");
        return errorResponse(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> methodNotAllowed(
            HttpRequestMethodNotSupportedException exception) {
        log.warn("Rejected unsupported email service method");
        return errorResponse(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> unsupportedMediaType(
            HttpMediaTypeNotSupportedException exception) {
        log.warn("Rejected unsupported email service media type");
        return errorResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> internalFailure(Exception exception) {
        log.error(
            "Email service request failed [error={}]",
            exception.getClass().getSimpleName()
        );
        return errorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Email service request failed"
        );
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus status,
            String message) {
        return ResponseEntity.status(status).body(
            Map.of("success", false, "message", message)
        );
    }
}
