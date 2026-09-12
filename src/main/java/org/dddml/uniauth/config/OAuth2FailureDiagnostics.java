package org.dddml.uniauth.config;

import org.springframework.web.client.RestClientResponseException;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

final class OAuth2FailureDiagnostics {

    private static final int ERROR_CODE_MAX_LENGTH = 64;

    private OAuth2FailureDiagnostics() {
    }

    static Summary summarize(Throwable exception) {
        Throwable current = exception;
        Throwable deepest = exception;
        while (current != null) {
            deepest = current;
            if (current instanceof HttpConnectTimeoutException) {
                return summary(Category.CONNECT_TIMEOUT, current);
            }
            if (current instanceof UnknownHostException) {
                return summary(Category.DNS, current);
            }
            if (current instanceof ConnectException) {
                return summary(Category.CONNECT, current);
            }
            if (current instanceof SSLException) {
                return summary(Category.TLS, current);
            }
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException) {
                return summary(Category.READ_TIMEOUT, current);
            }
            if (current instanceof RestClientResponseException) {
                return summary(Category.PROVIDER_HTTP, current);
            }
            current = current.getCause();
        }
        return summary(Category.OAUTH_PROTOCOL, deepest);
    }

    static String safeErrorCode(String errorCode) {
        if (errorCode == null
                || errorCode.isBlank()
                || errorCode.length() > ERROR_CODE_MAX_LENGTH) {
            return "unavailable";
        }
        for (int index = 0; index < errorCode.length(); index++) {
            char character = errorCode.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '_'
                    && character != '-'
                    && character != '.') {
                return "unavailable";
            }
        }
        return errorCode;
    }

    private static Summary summary(Category category, Throwable cause) {
        String causeType = cause == null
                ? "unavailable"
                : cause.getClass().getSimpleName();
        return new Summary(category, causeType);
    }

    enum Category {
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        DNS,
        CONNECT,
        TLS,
        PROVIDER_HTTP,
        OAUTH_PROTOCOL
    }

    record Summary(Category category, String causeType) {
    }
}
