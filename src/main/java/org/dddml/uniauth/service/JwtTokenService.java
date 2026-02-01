package org.dddml.uniauth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JWT Token生成和管理服务
 * 使用 RSA-2048 密钥对进行签名和验证
 * 支持 JWKS 和异构资源服务器集成
 */
@Service
@Getter
@ConfigurationProperties(prefix = "jwt")
public class JwtTokenService {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private static final int RSA_KEY_SIZE = 2048;

    // JWT配置属性
    private RsaConfig rsa;
    private ExpiresConfig expires;
    private TokenConfig token;

    // RSA配置内部类
    public static class RsaConfig {
        private String keyFile;

        public String getKeyFile() {
            return keyFile;
        }

        public void setKeyFile(String keyFile) {
            this.keyFile = keyFile;
        }
    }

    // Token过期时间配置内部类
    public static class ExpiresConfig {
        private long accessToken;
        private long refreshToken;

        public long getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(long accessToken) {
            this.accessToken = accessToken;
        }

        public long getRefreshToken() {
            return refreshToken;
        }

        public void setRefreshToken(long refreshToken) {
            this.refreshToken = refreshToken;
        }
    }

    // Token配置内部类
    public static class TokenConfig {
        private String issuer;
        private String audience;
        private String kid;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getKid() {
            return kid;
        }

        public void setKid(String kid) {
            this.kid = kid;
        }
    }

    public JwtTokenService() {
        // 构造函数中只初始化密钥对，不设置配置默认值
        // 配置默认值将在 @PostConstruct 方法中设置，确保 Spring 的配置注入已完成
        String rsaKeyFilePath = "rsa-keys.ser"; // 临时使用默认值
        KeyPair keyPair = loadOrGenerateKeyPair(rsaKeyFilePath);
        this.privateKey = keyPair.getPrivate();
        this.publicKey = keyPair.getPublic();
    }
    
    /**
     * 在 Spring 完成配置注入后执行初始化
     * 此时 @ConfigurationProperties 已经注入完成
     */
    @PostConstruct
    public void init() {
        // 设置配置默认值（如果配置文件中没有提供）
        if (rsa == null) {
            rsa = new RsaConfig();
        }
        if (rsa.getKeyFile() == null || rsa.getKeyFile().isEmpty()) {
            rsa.setKeyFile("rsa-keys.ser");
        }
        
        if (expires == null) {
            expires = new ExpiresConfig();
        }
        if (expires.getAccessToken() <= 0) {
            expires.setAccessToken(3600000); // 默认1小时
        }
        if (expires.getRefreshToken() <= 0) {
            expires.setRefreshToken(604800000); // 默认7天
        }
        
        if (token == null) {
            token = new TokenConfig();
        }
        if (token.getIssuer() == null || token.getIssuer().isEmpty()) {
            token.setIssuer("https://auth.example.com");
        }
        if (token.getAudience() == null || token.getAudience().isEmpty()) {
            token.setAudience("resource-server");
        }
        if (token.getKid() == null || token.getKid().isEmpty()) {
            token.setKid("key-1");
        }
        
        // 打印配置信息（此时配置已经注入完成）
        System.out.println("\n========================================");
        System.out.println("📋 JWT Configuration Status");
        System.out.println("========================================");
        System.out.println("✅ JwtTokenService initialized with RSA-2048 keys");
        System.out.println("   Public Key Algorithm: " + publicKey.getAlgorithm());
        System.out.println("   Key Size: " + RSA_KEY_SIZE);
        System.out.println("   Public Key Format: " + publicKey.getFormat());
        System.out.println("   Key File Path: " + rsa.getKeyFile());
        System.out.println("\n⏱️  Token Expiration Configuration:");
        System.out.println("   Access Token Expires In: " + expires.getAccessToken() / 1000 + " seconds (" + expires.getAccessToken() / 60000 + " minutes)");
        System.out.println("   Refresh Token Expires In: " + expires.getRefreshToken() / 1000 + " seconds (" + expires.getRefreshToken() / 86400000 + " days)");
        System.out.println("\n🎫 Token Claims Configuration:");
        System.out.println("   Token Issuer (iss): " + token.getIssuer());
        System.out.println("   Token Audience (aud): " + token.getAudience());
        System.out.println("   Token Key ID (kid): " + token.getKid());
        System.out.println("========================================\n");
        
        // 打印公钥的Base64编码，用于调试
        if (publicKey instanceof java.security.interfaces.RSAPublicKey) {
            java.security.interfaces.RSAPublicKey rsaPublicKey = (java.security.interfaces.RSAPublicKey) publicKey;
            System.out.println("🔐 RSA Public Key Details:");
            System.out.println("   RSA Public Key Modulus Length: " + rsaPublicKey.getModulus().bitLength());
            System.out.println("   RSA Public Key Exponent: " + rsaPublicKey.getPublicExponent());
            System.out.println("========================================\n");
        }
    }

    /**
     * 加载或生成 RSA 密钥对
     */
    private KeyPair loadOrGenerateKeyPair(String rsaKeyFilePath) {
        try {
            // 尝试从文件加载密钥对
            Path keyFile = Paths.get(rsaKeyFilePath);
            if (Files.exists(keyFile)) {
                System.out.println("🔑 Loading RSA key pair from file: " + rsaKeyFilePath);
                return loadKeyPairFromFile(keyFile);
            }
        } catch (Exception e) {
            System.out.println("⚠️ Failed to load key pair from file: " + e.getMessage());
        }

        // 生成新的密钥对
        System.out.println("🔄 Generating new RSA-2048 key pair...");
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(RSA_KEY_SIZE);
            KeyPair keyPair = keyGen.generateKeyPair();
            
            // 尝试保存到文件
            try {
                saveKeyPairToFile(keyPair, Paths.get(rsaKeyFilePath));
                System.out.println("💾 Key pair saved to: " + rsaKeyFilePath);
                System.out.println("\n⚠️  IMPORTANT: A new RSA key pair has been generated and saved to " + rsaKeyFilePath);
                System.out.println("   For production environments, it is recommended to:");
                System.out.println("   1. Backup this key file to a secure location");
                System.out.println("   2. Specify this key file path in your configuration using 'jwt.rsa.key-file' property");
                System.out.println("   3. Ensure this key file is not committed to version control\n");
            } catch (Exception e) {
                System.out.println("⚠️ Failed to save key pair to file: " + e.getMessage());
            }
            
            return keyPair;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }

    /**
     * 从文件加载密钥对
     */
    private KeyPair loadKeyPairFromFile(Path keyFile) throws Exception {
        byte[] keyData = Files.readAllBytes(keyFile);
        
        // 简单的格式：privateKey长度(4字节) + privateKeyData + publicKeyData
        int privateKeyLength = ((keyData[0] & 0xFF) << 24) |
                              ((keyData[1] & 0xFF) << 16) |
                              ((keyData[2] & 0xFF) << 8) |
                              (keyData[3] & 0xFF);
        
        byte[] privateKeyData = new byte[privateKeyLength];
        byte[] publicKeyData = new byte[keyData.length - 4 - privateKeyLength];
        
        System.arraycopy(keyData, 4, privateKeyData, 0, privateKeyLength);
        System.arraycopy(keyData, 4 + privateKeyLength, publicKeyData, 0, publicKeyData.length);
        
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyData);
        PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
        
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyData);
        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);
        
        KeyPair loadedKeyPair = new KeyPair(publicKey, privateKey);
        System.out.println("✅ RSA key pair loaded from file");
        return loadedKeyPair;
    }

    /**
     * 将密钥对保存到文件
     */
    private void saveKeyPairToFile(KeyPair keyPair, Path keyFile) throws Exception {
        byte[] privateKeyData = keyPair.getPrivate().getEncoded();
        byte[] publicKeyData = keyPair.getPublic().getEncoded();
        
        byte[] keyFileData = new byte[4 + privateKeyData.length + publicKeyData.length];
        
        // 写入 privateKey 长度
        keyFileData[0] = (byte) ((privateKeyData.length >> 24) & 0xFF);
        keyFileData[1] = (byte) ((privateKeyData.length >> 16) & 0xFF);
        keyFileData[2] = (byte) ((privateKeyData.length >> 8) & 0xFF);
        keyFileData[3] = (byte) (privateKeyData.length & 0xFF);
        
        System.arraycopy(privateKeyData, 0, keyFileData, 4, privateKeyData.length);
        System.arraycopy(publicKeyData, 0, keyFileData, 4 + privateKeyData.length, publicKeyData.length);
        
        Files.write(keyFile, keyFileData);
    }

    /**
     * 生成访问 Token
     */
    public String generateAccessToken(
            String username,
            String email,
            String userId,
            java.util.Set<String> authorities) {
        
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("email", email);
        claims.put("authorities", authorities);
        claims.put("type", "access");
        
        // OAuth2 标准声明
        long issuedAtMs = System.currentTimeMillis();
        long expiresInMs = expires.getAccessToken(); // 从配置文件读取
        
        claims.put("iss", token.getIssuer());
        claims.put("aud", token.getAudience());
        claims.put("jti", UUID.randomUUID().toString());

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setIssuedAt(new Date(issuedAtMs))
                .setExpiration(new Date(issuedAtMs + expiresInMs))
                .setHeaderParam("kid", token.getKid())  // 用于 JWKS 匹配
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    /**
     * 生成刷新 Token
     */
    public String generateRefreshToken(String username, String userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("type", "refresh");
        claims.put("jti", UUID.randomUUID().toString());
        
        long issuedAtMs = System.currentTimeMillis();
        long expiresInMs = expires.getRefreshToken(); // 从配置文件读取
        
        claims.put("iss", token.getIssuer());

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setIssuedAt(new Date(issuedAtMs))
                .setExpiration(new Date(issuedAtMs + expiresInMs))
                .setHeaderParam("kid", token.getKid())
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    /**
     * 生成测试 Token（用于测试场景）
     */
    public String generateTestToken(String username) {
        return generateAccessToken(username, username + "@example.com", UUID.randomUUID().toString(),
                new HashSet<>(Arrays.asList("ROLE_USER")));
    }

    /**
     * 验证 Refresh Token
     */
    public boolean validateRefreshToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 Token 中提取用户名
     */
    public String extractUsername(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract username from token", e);
        }
    }

    /**
     * 从 Token 中提取用户 ID
     */
    public String getUserIdFromToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .get("userId", String.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract user ID from token", e);
        }
    }

    /**
     * 获取 JWT 解码器
     * 用于 OAuth2 资源服务器验证 JWT Token
     */
    public org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey((java.security.interfaces.RSAPublicKey) publicKey).build();
    }

    // Getter和Setter方法
    public RsaConfig getRsa() {
        return rsa;
    }

    public void setRsa(RsaConfig rsa) {
        this.rsa = rsa;
    }

    public ExpiresConfig getExpires() {
        return expires;
    }

    public void setExpires(ExpiresConfig expires) {
        this.expires = expires;
    }

    public TokenConfig getToken() {
        return token;
    }

    public void setToken(TokenConfig token) {
        this.token = token;
    }
}
