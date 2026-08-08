import json
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

import app as resource_server


class ResourceServerTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.kid = "integration-test-key"
        jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(cls.private_key.public_key()))
        jwk["kid"] = cls.kid
        jwk["alg"] = "RS256"
        jwk["use"] = "sig"
        cls.jwks = {"keys": [jwk]}

        cls.rotated_private_key = rsa.generate_private_key(
            public_exponent=65537,
            key_size=2048,
        )
        cls.rotated_kid = "rotated-integration-test-key"
        rotated_jwk = json.loads(
            jwt.algorithms.RSAAlgorithm.to_jwk(
                cls.rotated_private_key.public_key()
            )
        )
        rotated_jwk["kid"] = cls.rotated_kid
        rotated_jwk["alg"] = "RS256"
        rotated_jwk["use"] = "sig"
        cls.rotated_jwk = rotated_jwk

    def setUp(self):
        resource_server.app.config.update(TESTING=True)
        self.client = resource_server.app.test_client()

    def token(self, **overrides):
        now = datetime.now(timezone.utc)
        claims = {
            "sub": "user-id",
            "userId": "user-id",
            "username": "integration-user",
            "email": "integration@example.invalid",
            "authorities": ["ROLE_USER"],
            "type": "access",
            "iss": resource_server.JWT_ISSUER,
            "aud": resource_server.JWT_AUDIENCE,
            "iat": now,
            "exp": now + timedelta(minutes=5),
        }
        claims.update(overrides)
        return jwt.encode(
            claims,
            self.private_key,
            algorithm="RS256",
            headers={"kid": self.kid},
        )

    def test_health_does_not_expose_authorization_server_configuration(self):
        response = self.client.get("/health")

        self.assertEqual(200, response.status_code)
        self.assertEqual(
            {"status": "ok", "service": "python-resource-server"},
            response.get_json(),
        )

    def test_protected_endpoint_rejects_missing_bearer_token(self):
        response = self.client.get("/api/protected")

        self.assertEqual(401, response.status_code)

    def test_protected_endpoint_rejects_malformed_authorization_header(self):
        response = self.client.get(
            "/api/protected",
            headers={"Authorization": "Basic not-a-bearer-token"},
        )

        self.assertEqual(401, response.status_code)
        self.assertEqual(
            {"error": "Invalid Authorization header format"},
            response.get_json(),
        )

    @patch.object(resource_server, "get_jwks")
    def test_valid_rs256_token_reaches_protected_endpoint(self, get_jwks):
        get_jwks.return_value = self.jwks

        response = self.client.get(
            "/api/protected",
            headers={"Authorization": f"Bearer {self.token()}"},
        )

        self.assertEqual(200, response.status_code)
        body = response.get_json()
        self.assertEqual("integration-user", body["user"]["username"])
        self.assertNotIn("token_claims", body["resource"])

    @patch.object(resource_server, "get_jwks")
    def test_unknown_kid_is_rejected_without_key_fallback(self, get_jwks):
        get_jwks.return_value = self.jwks
        token = jwt.encode(
            {
                "sub": "user-id",
                "iss": resource_server.JWT_ISSUER,
                "aud": resource_server.JWT_AUDIENCE,
            },
            self.private_key,
            algorithm="RS256",
            headers={"kid": "unknown-key"},
        )

        valid, result = resource_server.validate_token(token)

        self.assertFalse(valid)
        self.assertEqual("Invalid token", result)

    def test_missing_kid_is_rejected_before_jwks_lookup(self):
        now = datetime.now(timezone.utc)
        token = jwt.encode(
            {
                "sub": "user-id",
                "iss": resource_server.JWT_ISSUER,
                "aud": resource_server.JWT_AUDIENCE,
                "iat": now,
                "exp": now + timedelta(minutes=5),
            },
            self.private_key,
            algorithm="RS256",
        )

        with patch.object(resource_server, "get_jwks") as get_jwks:
            valid, result = resource_server.validate_token(token)

        self.assertFalse(valid)
        self.assertEqual("Invalid token", result)
        get_jwks.assert_not_called()

    @patch.object(resource_server, "get_jwks")
    def test_jwks_unavailability_fails_closed(self, get_jwks):
        get_jwks.return_value = None

        valid, result = resource_server.validate_token(self.token())
        response = self.client.get(
            "/api/protected",
            headers={"Authorization": f"Bearer {self.token()}"},
        )

        self.assertFalse(valid)
        self.assertEqual("Validation unavailable", result)
        self.assertEqual(401, response.status_code)
        self.assertEqual({"error": "Invalid token"}, response.get_json())

    @patch.object(resource_server, "get_jwks")
    def test_wrong_issuer_is_rejected(self, get_jwks):
        get_jwks.return_value = self.jwks

        valid, result = resource_server.validate_token(
            self.token(iss="https://wrong-issuer.example")
        )

        self.assertFalse(valid)
        self.assertEqual("Invalid token", result)

    @patch.object(resource_server, "get_jwks")
    def test_wrong_audience_is_rejected(self, get_jwks):
        get_jwks.return_value = self.jwks

        valid, result = resource_server.validate_token(
            self.token(aud="wrong-audience")
        )

        self.assertFalse(valid)
        self.assertEqual("Invalid token", result)

    @patch.object(resource_server, "get_jwks")
    def test_refresh_token_is_rejected(self, get_jwks):
        get_jwks.return_value = self.jwks

        valid, result = resource_server.validate_token(
            self.token(type="refresh")
        )

        self.assertFalse(valid)
        self.assertEqual("Invalid token", result)

    @patch.object(resource_server, "get_jwks")
    def test_token_without_type_is_rejected(self, get_jwks):
        get_jwks.return_value = self.jwks
        now = datetime.now(timezone.utc)
        token = jwt.encode(
            {
                "sub": "user-id",
                "userId": "user-id",
                "username": "integration-user",
                "iss": resource_server.JWT_ISSUER,
                "aud": resource_server.JWT_AUDIENCE,
                "iat": now,
                "exp": now + timedelta(minutes=5),
            },
            self.private_key,
            algorithm="RS256",
            headers={"kid": self.kid},
        )

        valid, result = resource_server.validate_token(token)

        self.assertFalse(valid)
        self.assertEqual("Invalid token", result)

    @patch.object(resource_server, "get_jwks")
    def test_expired_token_is_rejected(self, get_jwks):
        get_jwks.return_value = self.jwks

        valid, result = resource_server.validate_token(
            self.token(exp=datetime.now(timezone.utc) - timedelta(seconds=1))
        )

        self.assertFalse(valid)
        self.assertEqual("Token expired", result)

    @patch.object(resource_server, "get_jwks")
    def test_rotated_key_is_selected_by_kid(self, get_jwks):
        get_jwks.return_value = {
            "keys": [self.jwks["keys"][0], self.rotated_jwk]
        }
        now = datetime.now(timezone.utc)
        token = jwt.encode(
            {
                "sub": "rotated-user-id",
                "userId": "rotated-user-id",
                "username": "rotated-user",
                "type": "access",
                "iss": resource_server.JWT_ISSUER,
                "aud": resource_server.JWT_AUDIENCE,
                "iat": now,
                "exp": now + timedelta(minutes=5),
            },
            self.rotated_private_key,
            algorithm="RS256",
            headers={"kid": self.rotated_kid},
        )

        valid, result = resource_server.validate_token(token)

        self.assertTrue(valid)
        self.assertEqual("rotated-user-id", result["userId"])

    def test_non_rs256_algorithm_is_rejected_before_jwks_lookup(self):
        token = jwt.encode(
            {
                "sub": "user-id",
                "iss": resource_server.JWT_ISSUER,
                "aud": resource_server.JWT_AUDIENCE,
            },
            "test-secret",
            algorithm="HS256",
            headers={"kid": self.kid},
        )

        with patch.object(resource_server, "get_jwks") as get_jwks:
            valid, result = resource_server.validate_token(token)

        self.assertFalse(valid)
        self.assertEqual("Invalid token", result)
        get_jwks.assert_not_called()

    @patch.object(resource_server, "get_jwks")
    def test_valid_token_reaches_protected_info_endpoint(self, get_jwks):
        get_jwks.return_value = self.jwks

        response = self.client.get(
            "/api/protected/info",
            headers={"Authorization": f"Bearer {self.token()}"},
        )

        self.assertEqual(200, response.status_code)
        self.assertEqual(
            {
                "info": "This resource is protected by UniAuth",
                "allowed_resources": [
                    "/api/protected",
                    "/api/protected/info",
                ],
            },
            response.get_json(),
        )

    def test_protected_info_rejects_missing_bearer_token(self):
        response = self.client.get("/api/protected/info")

        self.assertEqual(401, response.status_code)
        self.assertEqual({"error": "Unauthorized"}, response.get_json())


if __name__ == "__main__":
    unittest.main()
