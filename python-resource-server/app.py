"""
Heterogeneous resource server implemented with Flask.

The service validates UniAuth access tokens against the configured JWKS endpoint.
"""

import json
import logging
import os
import time
from datetime import datetime

import jwt
import requests
from flask import Flask, jsonify, request
from flask_cors import CORS


def env_flag(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


app = Flask(__name__)
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO").upper())
logger = logging.getLogger(__name__)

AUTH_SERVER_URL = os.getenv("AUTH_SERVER_URL", "http://localhost:8081").rstrip("/")
JWKS_URL = os.getenv("JWKS_URL", f"{AUTH_SERVER_URL}/oauth2/jwks")
JWT_ISSUER = os.getenv("JWT_ISSUER", "https://auth.example.com")
JWT_AUDIENCE = os.getenv("JWT_AUDIENCE", "resource-server")
RESOURCE_SERVER_PORT = int(os.getenv("RESOURCE_SERVER_PORT", "5002"))
FLASK_DEBUG = env_flag("FLASK_DEBUG")
CORS_ALLOWED_ORIGINS = [
    origin.strip()
    for origin in os.getenv(
        "CORS_ALLOWED_ORIGINS",
        "http://localhost:5173,http://localhost:8081",
    ).split(",")
    if origin.strip()
]

CORS(
    app,
    resources={
        r"/*": {
            "origins": CORS_ALLOWED_ORIGINS,
            "methods": ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
            "allow_headers": ["Authorization", "Content-Type"],
            "supports_credentials": True,
        }
    },
)

jwks_cache = None
cache_time = 0.0
CACHE_DURATION = 3600


def get_jwks():
    """Fetch and cache the authorization server JWKS."""
    global jwks_cache, cache_time

    current_time = time.time()
    if jwks_cache and (current_time - cache_time) < CACHE_DURATION:
        return jwks_cache

    try:
        response = requests.get(JWKS_URL, timeout=10)
        response.raise_for_status()
        fetched_jwks = response.json()
        if not isinstance(fetched_jwks.get("keys"), list):
            logger.error("JWKS response is invalid")
            return None
        jwks_cache = fetched_jwks
        cache_time = current_time
        logger.info("JWKS refresh succeeded")
        return jwks_cache
    except (requests.RequestException, ValueError, TypeError):
        logger.error("JWKS refresh failed")
        return None


def validate_token(token):
    """Validate a JWT without logging token-derived identifiers or claims."""
    try:
        header = jwt.get_unverified_header(token)
    except jwt.InvalidTokenError:
        logger.warning("Token header validation failed")
        return False, "Invalid token"

    if header.get("alg") != "RS256":
        logger.warning("Token algorithm validation failed")
        return False, "Invalid token"

    kid = header.get("kid")
    if not isinstance(kid, str) or not kid:
        logger.warning("Token key identifier is missing")
        return False, "Invalid token"

    jwks = get_jwks()
    if not jwks:
        return False, "Validation unavailable"

    matching_jwk = next(
        (candidate for candidate in jwks.get("keys", []) if candidate.get("kid") == kid),
        None,
    )
    if matching_jwk is None:
        logger.warning("Token key identifier is unknown")
        return False, "Invalid token"

    try:
        key = jwt.algorithms.RSAAlgorithm.from_jwk(json.dumps(matching_jwk))
        decoded = jwt.decode(
            token,
            key,
            algorithms=["RS256"],
            audience=JWT_AUDIENCE,
            issuer=JWT_ISSUER,
            options={"verify_exp": True},
        )
        logger.info("Token validation succeeded")
        return True, decoded
    except jwt.ExpiredSignatureError:
        logger.warning("Token validation failed: expired")
        return False, "Token expired"
    except (jwt.InvalidTokenError, ValueError, TypeError):
        logger.warning("Token validation failed")
        return False, "Invalid token"


@app.route("/health", methods=["GET"])
def health():
    """Health check endpoint."""
    return jsonify({"status": "ok", "service": "python-resource-server"})


@app.route("/api/protected", methods=["GET"])
def protected_resource():
    """Return a protected resource for a valid bearer access token."""
    auth_header = request.headers.get("Authorization")
    if not auth_header:
        return jsonify({"error": "Authorization header required"}), 401
    if not auth_header.startswith("Bearer "):
        return jsonify({"error": "Invalid Authorization header format"}), 401

    valid, result = validate_token(auth_header[7:])
    if not valid:
        return jsonify({"error": "Invalid token"}), 401

    now = datetime.now().isoformat()
    return jsonify(
        {
            "message": "Access granted",
            "timestamp": now,
            "user": {
                "id": result.get("userId"),
                "username": result.get("username") or result.get("sub"),
                "email": result.get("email"),
                "authorities": result.get("authorities", []),
            },
            "resource": {
                "data": "This is protected data from Python resource server",
                "accessed_at": now,
            },
        }
    )


@app.route("/api/protected/info", methods=["GET"])
def protected_info():
    """Return non-sensitive metadata about the protected resource."""
    auth_header = request.headers.get("Authorization")
    if not auth_header or not auth_header.startswith("Bearer "):
        return jsonify({"error": "Unauthorized"}), 401

    valid, _ = validate_token(auth_header[7:])
    if not valid:
        return jsonify({"error": "Invalid token"}), 401

    return jsonify(
        {
            "info": "This resource is protected by UniAuth",
            "allowed_resources": ["/api/protected", "/api/protected/info"],
        }
    )


@app.errorhandler(404)
def not_found(_error):
    return jsonify({"error": "Endpoint not found"}), 404


@app.errorhandler(500)
def internal_error(_error):
    logger.error("Internal server error")
    return jsonify({"error": "Internal server error"}), 500


if __name__ == "__main__":
    logger.info("Starting Python resource server")
    app.run(
        host="0.0.0.0",
        port=RESOURCE_SERVER_PORT,
        debug=FLASK_DEBUG,
        use_reloader=FLASK_DEBUG,
    )
