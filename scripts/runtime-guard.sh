#!/usr/bin/env bash

# Shared startup guard for the repository's interactive launch scripts.

uniauth_require_env() {
    local variable_name="$1"
    if [ -z "${!variable_name:-}" ]; then
        echo "Error: required environment variable ${variable_name} is not set" >&2
        return 1
    fi
}

uniauth_require_oauth_credentials() {
    uniauth_require_env GOOGLE_CLIENT_ID || return 1
    uniauth_require_env GOOGLE_CLIENT_SECRET || return 1
    uniauth_require_env GITHUB_CLIENT_ID || return 1
    uniauth_require_env GITHUB_CLIENT_SECRET || return 1
    uniauth_require_env TWITTER_CLIENT_ID || return 1
    uniauth_require_env TWITTER_CLIENT_SECRET || return 1
}

uniauth_require_postgres() {
    uniauth_require_env POSTGRES_HOST || return 1
    uniauth_require_env POSTGRES_PORT || return 1
    uniauth_require_env POSTGRES_DATABASE || return 1
    uniauth_require_env POSTGRES_USER || return 1
    uniauth_require_env POSTGRES_PASSWORD || return 1
}

uniauth_require_nonproduction_database_name() {
    local database_name="$1"

    case "$database_name" in
        dev|dev_*|dev-*|*_dev|*-dev|test|test_*|test-*|*_test|*-test|demo|demo_*|demo-*|*_demo|*-demo|uniauth_test|uniauth_demo)
            ;;
        *)
            echo "Error: profile requires an explicitly named dev/test/demo PostgreSQL database" >&2
            return 1
            ;;
    esac
}

uniauth_require_disposable_database_name() {
    local database_name="$1"

    case "$database_name" in
        test|test_*|test-*|*_test|*-test|demo|demo_*|demo-*|*_demo|*-demo|uniauth_test|uniauth_demo)
            ;;
        *)
            echo "Error: test profile requires an explicitly disposable test/demo PostgreSQL database" >&2
            return 1
            ;;
    esac
}

uniauth_require_schema_owner() {
    if [ "${SPRING_FLYWAY_ENABLED:-true}" != "true" ]; then
        echo "Error: SPRING_FLYWAY_ENABLED must be exactly true" >&2
        return 1
    fi
}

uniauth_prepare_runtime() {
    local project_dir="$1"
    local profile="${SPRING_PROFILES_ACTIVE:-dev}"

    uniauth_require_schema_owner || return 1

    case "$profile" in
        dev)
            uniauth_require_postgres || return 1
            uniauth_require_nonproduction_database_name "$POSTGRES_DATABASE" || return 1
            export SPRING_PROFILES_ACTIVE=dev
            echo "Runtime profile: dev (explicit PostgreSQL target)"
            ;;
        test)
            uniauth_require_postgres || return 1
            uniauth_require_disposable_database_name "$POSTGRES_DATABASE" || return 1
            export SPRING_PROFILES_ACTIVE=test
            echo "Runtime profile: test (explicit disposable PostgreSQL target)"
            ;;
        prod)
            uniauth_require_postgres || return 1
            export SPRING_PROFILES_ACTIVE=prod
            echo "Runtime profile: prod (explicit PostgreSQL target)"
            ;;
        *)
            echo "Error: SPRING_PROFILES_ACTIVE must be exactly dev, test, or prod" >&2
            return 1
            ;;
    esac

    if [ "${APP_DEMO_DATA_ENABLED:-false}" = "true" ] \
        && [ "${APP_DEMO_DATA_DISPOSABLE:-false}" != "true" ]; then
        echo "Error: demo data requires APP_DEMO_DATA_DISPOSABLE=true" >&2
        return 1
    fi
}
