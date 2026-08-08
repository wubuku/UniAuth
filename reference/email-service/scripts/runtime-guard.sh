#!/usr/bin/env bash

# Shared startup checks for the standalone email-service reference component.

email_service_require_env() {
    local variable_name="$1"
    if [ -z "${!variable_name:-}" ]; then
        echo "Error: required environment variable ${variable_name} is not set" >&2
        return 1
    fi
}

email_service_require_one_of() {
    local first_name="$1"
    local second_name="$2"
    if [ -z "${!first_name:-}" ] && [ -z "${!second_name:-}" ]; then
        echo "Error: one of ${first_name} or ${second_name} must be set" >&2
        return 1
    fi
}

email_service_require_integer_range() {
    local variable_name="$1"
    local value="$2"
    local minimum="$3"
    local maximum="$4"

    if ! [[ "$value" =~ ^[0-9]+$ ]] || [ "${#value}" -gt 9 ]; then
        echo "Error: ${variable_name} must be an integer from ${minimum} to ${maximum}" >&2
        return 1
    fi

    local numeric_value=$((10#$value))
    if (( numeric_value < minimum || numeric_value > maximum )); then
        echo "Error: ${variable_name} must be an integer from ${minimum} to ${maximum}" >&2
        return 1
    fi
}

email_service_require_boolean() {
    local variable_name="$1"
    local value="$2"

    case "$value" in
        true|false)
            ;;
        *)
            echo "Error: ${variable_name} must be exactly true or false" >&2
            return 1
            ;;
    esac
}

email_service_is_loopback() {
    case "$1" in
        localhost|127.*|::1|\[::1\])
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

email_service_require_dedicated_database() {
    local profile="$1"
    local database_name="$2"

    case "$database_name" in
        *email*|*mail*)
            ;;
        *)
            echo "Error: email service database name must contain email or mail" >&2
            return 1
            ;;
    esac

    case "$database_name" in
        blacksheep|blacksheep_*|blacksheep-*|postgres|template0|template1|uniauth|uniauth_dev|uniauth_test)
            echo "Error: refusing a shared or reserved PostgreSQL database" >&2
            return 1
            ;;
    esac

    if [ "$profile" = "dev" ]; then
        case "$database_name" in
            *dev*|*test*|*demo*|*local*)
                ;;
            *)
                echo "Error: dev profile requires an email database named dev/test/demo/local" >&2
                return 1
                ;;
        esac
    fi
}

email_service_validate_env_file() {
    local env_file="$1"
    local mode

    if [ -L "$env_file" ]; then
        echo "Error: email service environment file must not be a symbolic link" >&2
        return 1
    fi
    if [ ! -f "$env_file" ]; then
        echo "Error: email service environment file does not exist" >&2
        return 1
    fi

    if mode="$(stat -f '%Lp' "$env_file" 2>/dev/null)"; then
        :
    elif mode="$(stat -c '%a' "$env_file" 2>/dev/null)"; then
        :
    else
        echo "Error: unable to inspect email service environment file permissions" >&2
        return 1
    fi

    if (( (8#$mode & 077) != 0 )); then
        echo "Error: email service environment file must not be accessible by group or others" >&2
        return 1
    fi
}

email_service_prepare_runtime() {
    local profile="${SPRING_PROFILES_ACTIVE:-}"
    local bind_address="${EMAIL_SERVICE_BIND_ADDRESS:-127.0.0.1}"
    local smtp_auth="${SMTP_AUTH:-true}"
    local smtp_starttls_enable="${SMTP_STARTTLS_ENABLE:-true}"
    local smtp_starttls_required="${SMTP_STARTTLS_REQUIRED:-true}"
    local smtp_ssl_enable="${SMTP_SSL_ENABLE:-false}"
    local smtp_ssl_check_server_identity="${SMTP_SSL_CHECK_SERVER_IDENTITY:-true}"
    local smtp_connection_timeout="${SMTP_CONNECTION_TIMEOUT_MS:-10000}"
    local smtp_read_timeout="${SMTP_READ_TIMEOUT_MS:-10000}"
    local smtp_write_timeout="${SMTP_WRITE_TIMEOUT_MS:-10000}"
    local recovery_enabled="${EMAIL_RECOVERY_ENABLED:-true}"
    local recovery_scan_interval="${EMAIL_RECOVERY_SCAN_INTERVAL_MINUTES:-5}"
    local stuck_timeout_minutes="${EMAIL_STUCK_TIMEOUT_MINUTES:-10}"
    local api_key="${EMAIL_SERVICE_API_KEY:-}"

    case "$profile" in
        dev|prod)
            ;;
        *)
            echo "Error: SPRING_PROFILES_ACTIVE must be exactly dev or prod" >&2
            return 1
            ;;
    esac

    email_service_require_env EMAIL_POSTGRES_HOST || return 1
    email_service_require_env EMAIL_POSTGRES_PORT || return 1
    email_service_require_env EMAIL_POSTGRES_DATABASE || return 1
    email_service_require_env EMAIL_POSTGRES_USER || return 1
    email_service_require_env EMAIL_POSTGRES_PASSWORD || return 1
    email_service_require_dedicated_database \
        "$profile" \
        "$EMAIL_POSTGRES_DATABASE" || return 1

    email_service_require_env SMTP_HOST || return 1
    email_service_require_env SMTP_PORT || return 1
    email_service_require_one_of EMAIL_FROM_ADDRESS APP_MAIL_FROM_EMAIL || return 1

    email_service_require_boolean SMTP_AUTH "$smtp_auth" || return 1
    email_service_require_boolean \
        SMTP_STARTTLS_ENABLE "$smtp_starttls_enable" || return 1
    email_service_require_boolean \
        SMTP_STARTTLS_REQUIRED "$smtp_starttls_required" || return 1
    email_service_require_boolean SMTP_SSL_ENABLE "$smtp_ssl_enable" || return 1
    email_service_require_boolean \
        SMTP_SSL_CHECK_SERVER_IDENTITY \
        "$smtp_ssl_check_server_identity" || return 1

    if [ "$smtp_auth" = "true" ]; then
        email_service_require_one_of SMTP_USERNAME SPRING_MAIL_USERNAME || return 1
        email_service_require_one_of SMTP_PASSWORD SPRING_MAIL_PASSWORD || return 1
    fi

    if [ "$smtp_starttls_required" = "true" ] \
        && [ "$smtp_starttls_enable" != "true" ]; then
        echo "Error: SMTP_STARTTLS_REQUIRED=true requires SMTP_STARTTLS_ENABLE=true" >&2
        return 1
    fi
    if [ "$smtp_ssl_enable" = "true" ] \
        && [ "$smtp_starttls_enable" = "true" ]; then
        echo "Error: SMTP_SSL_ENABLE=true cannot be combined with SMTP_STARTTLS_ENABLE=true" >&2
        return 1
    fi
    if [ "$profile" = "prod" ]; then
        if [ "$smtp_ssl_enable" != "true" ] \
            && ! {
                [ "$smtp_starttls_enable" = "true" ] \
                    && [ "$smtp_starttls_required" = "true" ]
            }; then
            echo "Error: production SMTP requires forced STARTTLS or implicit SSL" >&2
            return 1
        fi
        if [ "$smtp_ssl_check_server_identity" != "true" ]; then
            echo "Error: production SMTP requires server identity verification" >&2
            return 1
        fi
    fi

    email_service_require_integer_range \
        SMTP_CONNECTION_TIMEOUT_MS "$smtp_connection_timeout" 100 600000 || return 1
    email_service_require_integer_range \
        SMTP_READ_TIMEOUT_MS "$smtp_read_timeout" 100 600000 || return 1
    email_service_require_integer_range \
        SMTP_WRITE_TIMEOUT_MS "$smtp_write_timeout" 100 600000 || return 1
    email_service_require_integer_range \
        EMAIL_RECOVERY_SCAN_INTERVAL_MINUTES \
        "$recovery_scan_interval" \
        1 \
        10080 || return 1
    email_service_require_integer_range \
        EMAIL_STUCK_TIMEOUT_MINUTES "$stuck_timeout_minutes" 1 10080 || return 1

    email_service_require_boolean EMAIL_RECOVERY_ENABLED "$recovery_enabled" || return 1

    if [ "${#api_key}" -gt 1024 ] \
        || [[ "$api_key" == *$'\r'* ]] \
        || [[ "$api_key" == *$'\n'* ]]; then
        echo "Error: EMAIL_SERVICE_API_KEY must be at most 1024 characters without CR or LF" >&2
        return 1
    fi

    if [ "$recovery_enabled" = "true" ]; then
        local delivery_budget_ms
        local stuck_timeout_ms
        delivery_budget_ms=$((
            10#$smtp_connection_timeout
            + 10#$smtp_read_timeout
            + 10#$smtp_write_timeout
        ))
        stuck_timeout_ms=$((10#$stuck_timeout_minutes * 60000))
        if (( delivery_budget_ms >= stuck_timeout_ms )); then
            echo "Error: EMAIL_STUCK_TIMEOUT_MINUTES must exceed the combined SMTP timeout budget" >&2
            return 1
        fi
    fi

    if ! email_service_is_loopback "$bind_address" && [ -z "$api_key" ]; then
        echo "Error: EMAIL_SERVICE_API_KEY is required for non-loopback binding" >&2
        return 1
    fi

    if [ "$profile" = "prod" ] && [ "$recovery_enabled" != "true" ]; then
        echo "Error: production email delivery requires recovery processing" >&2
        return 1
    fi

    echo "Email service runtime profile: ${profile}"
    echo "Email service database target passed dedicated-name checks"
}
