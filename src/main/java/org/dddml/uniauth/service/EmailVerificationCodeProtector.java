package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.config.EmailVerificationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class EmailVerificationCodeProtector {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final EmailVerificationProperties properties;

    public String currentKeyId() {
        return properties.getHmacKeyId();
    }

    public String deriveCode(String challengeHandle, String keyId) {
        byte[] digest = hmac(keyId, "delivery:" + challengeHandle);
        long value = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        long positive = value == Long.MIN_VALUE ? 0 : Math.abs(value);
        int modulus = (int) Math.pow(10, properties.getCodeLength());
        return String.format("%0" + properties.getCodeLength() + "d", positive % modulus);
    }

    public String digest(String challengeHandle, String code, String keyId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmac(keyId, "verify:" + challengeHandle + ":" + code)
        );
    }

    public boolean matches(
            String challengeHandle,
            String suppliedCode,
            String keyId,
            String expectedDigest) {
        if (suppliedCode == null || expectedDigest == null) {
            return false;
        }
        byte[] expected = expectedDigest.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = digest(challengeHandle, suppliedCode, keyId)
                .getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] hmac(String keyId, String value) {
        if (!properties.getHmacKeyId().equals(keyId)) {
            throw new IllegalArgumentException("Unknown verification key");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getHmacKey().getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            ));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Verification code protection failed", exception);
        }
    }
}
