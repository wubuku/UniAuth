package org.dddml.uniauth.util;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;

@Slf4j
public class Web3SignatureUtils {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private Web3SignatureUtils() {
    }

    public static boolean isValidAddress(String address) {
        if (address == null || !address.matches("^0x[a-fA-F0-9]{40}$")) {
            return false;
        }
        return true;
    }

    public static String normalizeAddress(String address) {
        if (address == null) {
            return null;
        }
        return address.toLowerCase();
    }

    public static boolean verifySignature(String message, String signature, String expectedAddress) {
        try {
            String recoveredAddress = recoverAddress(message, signature);
            log.debug("Signature verification - expected: {}, recovered: {}", expectedAddress, recoveredAddress);
            return expectedAddress.equalsIgnoreCase(recoveredAddress);
        } catch (Exception e) {
            log.error("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    public static String recoverAddress(String message, String signature) throws Exception {
        if (signature == null || !signature.startsWith("0x")) {
            throw new IllegalArgumentException("Invalid signature format: must start with 0x");
        }

        byte[] signatureBytes = Numeric.hexStringToByteArray(signature);

        if (signatureBytes.length != 65) {
            throw new IllegalArgumentException("Invalid signature length: expected 65 bytes, got " + signatureBytes.length);
        }

        // Extract v, r, s
        byte v = signatureBytes[64];
        if (v < 27) {
            v += 27;
        }

        byte[] r = Arrays.copyOfRange(signatureBytes, 0, 32);
        byte[] s = Arrays.copyOfRange(signatureBytes, 32, 64);

        BigInteger rBI = new BigInteger(1, r);
        BigInteger sBI = new BigInteger(1, s);

        log.debug("Signature components - v: {}, r: {}, s: {}", v, rBI.toString(16), sBI.toString(16));

        // EIP-191 message prefix
        String prefix = "\u0019Ethereum Signed Message:\n" + message.length();
        byte[] msgHash = Hash.sha3((prefix + message).getBytes(StandardCharsets.UTF_8));

        log.debug("Message hash: {}", Numeric.toHexString(msgHash));

        // Use v value to calculate recovery ID
        int recId = v - 27;

        // Validate recId
        if (recId < 0 || recId > 3) {
            throw new Exception("Invalid recovery id: " + recId);
        }

        ECDSASignature ecdsaSignature = new ECDSASignature(rBI, sBI);

        // Directly use the correct recovery ID
        BigInteger publicKey = Sign.recoverFromSignature(recId, ecdsaSignature, msgHash);

        if (publicKey == null) {
            throw new Exception("Failed to recover public key from signature");
        }

        String address = "0x" + Keys.getAddress(publicKey);
        log.debug("Recovered address: {}", address);

        return address.toLowerCase();
    }

    public static boolean verifySignatureStructure(String signature) {
        if (signature == null || !signature.startsWith("0x")) {
            return false;
        }

        String hexPart = signature.substring(2);
        if (hexPart.length() != 130) {
            return false;
        }

        try {
            Numeric.hexStringToByteArray(signature);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
