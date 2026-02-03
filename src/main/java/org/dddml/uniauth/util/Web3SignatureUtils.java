package org.dddml.uniauth.util;

import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Slf4j
public class Web3SignatureUtils {

    private static final String ETH_SIGN_PREFIX = "\u0019Ethereum Signed Message:\n";

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

        byte[] r = Arrays.copyOfRange(signatureBytes, 0, 32);
        byte[] s = Arrays.copyOfRange(signatureBytes, 32, 64);
        byte v = signatureBytes[64];

        if (v < 27) {
            v += 27;
        }

        String prefixedMessage = ETH_SIGN_PREFIX + message.length() + message;
        byte[] msgHash = Hash.sha3(prefixedMessage.getBytes(StandardCharsets.UTF_8));

        BigInteger rBI = new BigInteger(1, r);
        BigInteger sBI = new BigInteger(1, s);
        BigInteger publicKey = recoverPublicKey(rBI, sBI, v, msgHash);

        if (publicKey == null) {
            throw new Exception("Failed to recover public key from signature");
        }

        String address = "0x" + Keys.getAddress(publicKey);
        return address.toLowerCase();
    }

    private static BigInteger recoverPublicKey(BigInteger r, BigInteger s, byte v, byte[] messageHash) {
        BigInteger n = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

        BigInteger x = r.subtract(BigInteger.ONE).divide(BigInteger.valueOf(2)).add(BigInteger.ONE);

        for (int recId = 0; recId < 2; recId++) {
            try {
                BigInteger xCoord = x.add(BigInteger.valueOf(recId)).mod(n);

                BigInteger ySquared = xCoord.modPow(BigInteger.valueOf(3), n).add(BigInteger.valueOf(7)).mod(n);
                BigInteger y = ySquared.modPow(n.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), n);

                boolean yBit = y.testBit(0);
                boolean vBit = ((v - 27) & 1) == 1;
                if (yBit != vBit) {
                    y = n.subtract(y);
                }

                BigInteger e = new BigInteger(1, messageHash);
                BigInteger eNeg = e.negate().mod(n);

                BigInteger rInv = r.modInverse(n);
                BigInteger sR = s.multiply(rInv).mod(n);
                BigInteger eRs = eNeg.multiply(rInv).mod(n);

                BigInteger gx = new BigInteger("79BE667E9DC2308BBF39A2E6B79C4480BA2F6E09C3A7B3E6B2BB8A4EBCE3A3D0E", 16);
                BigInteger gy = new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);

                BigInteger Qx = gy.multiply(sR).subtract(xCoord.multiply(eRs)).mod(n);
                BigInteger Qy = xCoord.multiply(sR).add(gy.multiply(eRs)).mod(n);

                if (Qx.equals(BigInteger.ZERO) && Qy.equals(BigInteger.ZERO)) {
                    continue;
                }

                String publicKeyHex = "04" + Qx.toString(16) + Qy.toString(16);
                BigInteger Q = new BigInteger(publicKeyHex, 16);

                return Q;
            } catch (Exception e) {
                continue;
            }
        }

        return null;
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

