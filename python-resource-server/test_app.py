import json
import unittest
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

    def setUp(self):
        resource_server.app.config.update(TESTING=True)
        self.client = resource_server.app.test_client()

    def token(self, **overrides):
        claims = {
            "sub": "user-id",
            "userId": "user-id",
            "username": "integration-user",
            "email": "integration@example.invalid",
            "authorities": ["ROLE_USER"],
            "iss": resource_server.JWT_ISSUER,
            "aud": resource_server.JWT_AUDIENCE,
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


if __name__ == "__main__":
    unittest.main()
