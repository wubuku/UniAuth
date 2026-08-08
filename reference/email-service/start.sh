#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${EMAIL_SERVICE_ENV_FILE:-$PROJECT_DIR/.env}"

# shellcheck source=scripts/runtime-guard.sh
source "$PROJECT_DIR/scripts/runtime-guard.sh"

if [ -f "$ENV_FILE" ] || [ -n "${EMAIL_SERVICE_ENV_FILE:-}" ]; then
    email_service_validate_env_file "$ENV_FILE"
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
fi

email_service_prepare_runtime

cd "$PROJECT_DIR"
exec mvn spring-boot:run \
    -Dspring-boot.run.profiles="$SPRING_PROFILES_ACTIVE"
