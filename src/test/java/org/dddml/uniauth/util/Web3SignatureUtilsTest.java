package org.dddml.uniauth.util;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Web3 wallet authentication is a credential boundary. These tests pin the
 * fail-closed behavior: malformed addresses and signatures must be rejected
 * instead of being recovered into an unexpected signer.
 */
class Web3SignatureUtilsTest {

    private static final String VALID_ADDRESS =
            "0x1234567890abcdef1234567890abcdef12345678";
    private static final String VALID_SPELLED_ADDRESS =
            "0x1234567890ABCDEF1234567890ABCDEF12345678";

    @Test
    void isValidAddressAcceptsOnlyTwentyByteHexValues() {
        assertThat(Web3SignatureUtils.isValidAddress(null)).isFalse();
        assertThat(Web3SignatureUtils.isValidAddress("1234567890abcdef1234567890abcdef12345678")).isFalse();
        assertThat(Web3SignatureUtils.isValidAddress("0x123")).isFalse();
        assertThat(Web3SignatureUtils.isValidAddress(
                "0x1234567890abcdef1234567890abcdef1234567g")).isFalse();
        assertThat(Web3SignatureUtils.isValidAddress(VALID_ADDRESS)).isTrue();
        assertThat(Web3SignatureUtils.isValidAddress(VALID_SPELLED_ADDRESS)).isTrue();
    }

    @Test
    void normalizeAddressLowercasesAndKeepsNull() {
        assertThat(Web3SignatureUtils.normalizeAddress(null)).isNull();
        assertThat(Web3SignatureUtils.normalizeAddress(VALID_SPELLED_ADDRESS))
                .isEqualTo(VALID_ADDRESS);
    }

    @Test
    void verifySignatureStructureRejectsMalformedInputs() {
        String valid = "0x" + "11".repeat(65);
        assertThat(Web3SignatureUtils.verifySignatureStructure(null)).isFalse();
        assertThat(Web3SignatureUtils.verifySignatureStructure(
                "11".repeat(65))).isFalse();
        assertThat(Web3SignatureUtils.verifySignatureStructure(
                "0x" + "11".repeat(64))).isFalse();
        assertThat(Web3SignatureUtils.verifySignatureStructure(
                "0x" + "11".repeat(66))).isFalse();
        // The structure check is length-only by contract: web3j's hex parser
        // maps invalid digits to garbage bytes instead of throwing, so a
        // 130-character non-hex payload still passes here. Production uses
        // verifySignature, which hashes those bytes and fails the address
        // comparison. This assertion documents that boundary rather than
        // pretending the structure check validates hex content.
        assertThat(Web3SignatureUtils.verifySignatureStructure(
                "0x" + "zz".repeat(65))).isTrue();
        assertThat(Web3SignatureUtils.verifySignatureStructure(valid)).isTrue();
    }

    @Test
    void recoversTheSignerFromARealEthereumSignature() throws Exception {
        ECKeyPair keyPair = testKeyPair();
        String expected = expectedAddress(keyPair);
        Sign.SignatureData signature = sign(MESSAGE, keyPair);
        String encoded = encode(signature, signature.getV()[0]);

        assertThat(Web3SignatureUtils.recoverAddress(MESSAGE, encoded))
                .isEqualTo(expected);
        assertThat(Web3SignatureUtils.verifySignature(
                MESSAGE, encoded, expected)).isTrue();
        assertThat(Web3SignatureUtils.verifySignature(
                MESSAGE, encoded, "0x1234567890abcdef1234567890abcdef12345678"))
                .isFalse();
    }

    @Test
    void acceptsBothLegacyAndNormalizedVValues() throws Exception {
        // Some wallets emit v = 27/28 (legacy) and others v = 0/1. Both must
        // recover the same signer, and v = 27 itself must not be shifted.
        ECKeyPair keyPair = testKeyPair();
        String expected = expectedAddress(keyPair);
        Sign.SignatureData signature = sign(MESSAGE, keyPair);
        int legacyV = signature.getV()[0];
        int normalizedV = legacyV - 27;

        assertThat(Web3SignatureUtils.recoverAddress(
                MESSAGE, encode(signature, legacyV)))
                .isEqualTo(expected);
        assertThat(Web3SignatureUtils.recoverAddress(
                MESSAGE, encode(signature, normalizedV)))
                .isEqualTo(expected);
    }

    @Test
    void treatsRecoveryIdThreeAsAnInRangeValue() {
        // v = 0x1e normalizes to recovery id 3. The point may be
        // unrecoverable, but it must not be rejected by the range check: the
        // call has to reach curve recovery and then fail there. Asserting that
        // an exception is thrown (rather than conditionally inspecting a
        // possibly-null value) keeps this test honest.
        Throwable thrown = catchThrowable(() -> Web3SignatureUtils.recoverAddress(
                "message",
                "0x" + "00".repeat(64) + "1e"
        ));
        assertThat(thrown)
                .as("recovery id 3 must pass the range check and fail in recovery")
                .isNotNull();
        assertThat(thrown.getMessage()).doesNotContain("Invalid recovery id");
    }

    private static final String MESSAGE =
            "UniAuth wants you to sign in with your Ethereum account:\n"
                    + VALID_ADDRESS + "\n\nSign in to UniAuth";

    private static ECKeyPair testKeyPair() {
        return ECKeyPair.create(BigInteger.valueOf(1234));
    }

    private static String expectedAddress(ECKeyPair keyPair) {
        return "0x" + Keys.getAddress(keyPair);
    }

    private static Sign.SignatureData sign(String message, ECKeyPair keyPair) {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        byte[] prefixBytes = (
                "\u0019Ethereum Signed Message:\n" + messageBytes.length
        ).getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[prefixBytes.length + messageBytes.length];
        System.arraycopy(prefixBytes, 0, payload, 0, prefixBytes.length);
        System.arraycopy(
                messageBytes,
                0,
                payload,
                prefixBytes.length,
                messageBytes.length
        );
        return Sign.signMessage(Hash.sha3(payload), keyPair, false);
    }

    private static String encode(Sign.SignatureData signature, int v) {
        return "0x"
                + String.format("%064x", new BigInteger(1, signature.getR()))
                + String.format("%064x", new BigInteger(1, signature.getS()))
                + String.format("%02x", v);
    }

    @Test
    void recoverAddressRejectsMissingPrefixAndWrongLength() {
        assertThatThrownBy(() -> Web3SignatureUtils.recoverAddress(
                "message",
                null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Web3SignatureUtils.recoverAddress(
                "message",
                "11".repeat(65)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Web3SignatureUtils.recoverAddress(
                "message",
                "0x" + "11".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recoverAddressRejectsAnOutOfRangeRecoveryId() {
        // v = 0x04 is normalized to 31, which yields recovery id 4.
        String signature = "0x" + "00".repeat(64) + "04";

        assertThatThrownBy(() -> Web3SignatureUtils.recoverAddress(
                "message",
                signature
        )).hasMessageContaining("Invalid recovery id");
    }

    @Test
    void recoverAddressThrowsWhenNoPublicKeyCanBeRecovered() {
        // r and s are zero, so no signer can be derived from this signature.
        String signature = "0x" + "00".repeat(64) + "1b";

        assertThatThrownBy(() -> Web3SignatureUtils.recoverAddress(
                "message",
                signature
        )).isInstanceOf(Exception.class);
    }

    @Test
    void verifySignatureReturnsFalseInsteadOfPropagatingFailures() {
        String malformed = "0x" + "11".repeat(64);

        assertThat(Web3SignatureUtils.verifySignature(
                "message",
                malformed,
                VALID_ADDRESS
        )).isFalse();
        assertThat(Web3SignatureUtils.verifySignature(
                "message",
                null,
                VALID_ADDRESS
        )).isFalse();
        // A null expected address must not throw through the credential check.
        assertThat(Web3SignatureUtils.verifySignature(
                "message",
                "0x" + "11".repeat(65),
                null
        )).isFalse();
        // The structure check is length-only; non-hex payloads must still be
        // rejected by the signature verification boundary.
        assertThat(Web3SignatureUtils.verifySignature(
                "message",
                "0x" + "zz".repeat(65),
                VALID_ADDRESS
        )).isFalse();
    }

    @Test
    void verifySignatureRejectsAValidlyShapedSignatureFromAnotherKey() {
        String foreignSignature = "0x" + "11".repeat(65);

        assertThat(Web3SignatureUtils.verifySignature(
                "message",
                foreignSignature,
                VALID_ADDRESS
        )).isFalse();
    }
}
