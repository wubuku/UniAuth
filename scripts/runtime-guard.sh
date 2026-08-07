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
    uniauth_require_env GOOGLE_CLIENT_ID
    uniauth_require_env GOOGLE_CLIENT_SECRET
    uniauth_require_env GITHUB_CLIENT_ID
    uniauth_require_env GITHUB_CLIENT_SECRET
    uniauth_require_env TWITTER_CLIENT_ID
    uniauth_require_env TWITTER_CLIENT_SECRET
}

uniauth_prepare_runtime() {
    local project_dir="$1"
    local profile="${SPRING_PROFILES_ACTIVE:-dev}"

    case "$profile" in
        dev)
            export SPRING_PROFILES_ACTIVE=dev
            export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:sqlite:${project_dir}/uniauth-demo.db}"
            export SPRING_SESSION_STORE_TYPE="${SPRING_SESSION_STORE_TYPE:-none}"
            echo "Runtime profile: dev (isolated SQLite target)"
            ;;
        test)
            uniauth_require_env POSTGRES_HOST
            uniauth_require_env POSTGRES_PORT
            uniauth_require_env POSTGRES_DATABASE
            uniauth_require_env POSTGRES_USER
            uniauth_require_env POSTGRES_PASSWORD
            case "$POSTGRES_DATABASE" in
                test|test_*|test-*|demo|demo_*|demo-*|uniauth_test|uniauth_test_*|uniauth-test|uniauth-test-*|uniauth_demo|uniauth_demo_*|uniauth-demo|uniauth-demo-*)
                    ;;
                *)
                    echo "Error: test profile requires a clearly disposable test/demo database name" >&2
                    return 1
                    ;;
            esac
            export SPRING_PROFILES_ACTIVE=test
            echo "Runtime profile: test (explicit disposable PostgreSQL target)"
            ;;
        prod)
            uniauth_require_env POSTGRES_HOST
            uniauth_require_env POSTGRES_PORT
            uniauth_require_env POSTGRES_DATABASE
            uniauth_require_env POSTGRES_USER
            uniauth_require_env POSTGRES_PASSWORD
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
