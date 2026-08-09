package org.dddml.uniauth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.nio.file.FileStore;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;

/**
 * JWT Token生成和管理服务
 * 使用 RSA-2048 密钥对进行签名和验证
 * 支持 JWKS 和异构资源服务器集成
 */
@Service
@Getter
@ConfigurationProperties(prefix = "jwt")
@Slf4j
public class JwtTokenService {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final Path rsaKeyFile;
    private static final int RSA_KEY_SIZE = 2048;
    private static final Set<PosixFilePermission> PRIVATE_KEY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

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

    public JwtTokenService(@Value("${jwt.rsa.key-file}") String rsaKeyFilePath) {
        if (rsaKeyFilePath == null || rsaKeyFilePath.isBlank()) {
            throw new IllegalArgumentException("jwt.rsa.key-file must be configured");
        }
        this.rsaKeyFile = Path.of(rsaKeyFilePath).toAbsolutePath().normalize();
        KeyPair keyPair = loadOrGenerateKeyPair(rsaKeyFile);
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
        rsa.setKeyFile(rsaKeyFile.toString());
        
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
        
        log.info("JWT signing service initialized with RSA-{} keys", RSA_KEY_SIZE);
    }

    /**
     * 加载或生成 RSA 密钥对
     */
    private KeyPair loadOrGenerateKeyPair(Path keyFile) {
        if (Files.exists(keyFile)) {
            try {
                requirePrivateKeyPermissions(keyFile);
                return loadKeyPairFromFile(keyFile);
            } catch (Exception e) {
                throw new IllegalStateException("Configured RSA key file could not be loaded", e);
            }
        }

        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(RSA_KEY_SIZE);
            KeyPair keyPair = keyGen.generateKeyPair();
            saveKeyPairToFile(keyPair, keyFile);
            log.warn("A new RSA key pair was generated; verify external key management before production use");
            return keyPair;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate and persist RSA key pair", e);
        }
    }

    /**
     * 从文件加载密钥对
     */
    private KeyPair loadKeyPairFromFile(Path keyFile) throws Exception {
        byte[] keyData = Files.readAllBytes(keyFile);
        if (keyData.length < 5) {
            throw new IllegalArgumentException("RSA key file is truncated");
        }
        
        // 简单的格式：privateKey长度(4字节) + privateKeyData + publicKeyData
        int privateKeyLength = ((keyData[0] & 0xFF) << 24) |
                              ((keyData[1] & 0xFF) << 16) |
                              ((keyData[2] & 0xFF) << 8) |
                              (keyData[3] & 0xFF);
        if (privateKeyLength <= 0 || privateKeyLength >= keyData.length - 4) {
            throw new IllegalArgumentException("RSA key file has an invalid private key length");
        }
        
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
        log.info("RSA key pair loaded");
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
        
        Path parent = keyFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(
                keyFile,
                keyFileData,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        restrictKeyFilePermissions(keyFile);
        requirePrivateKeyPermissions(keyFile);
    }

    private void restrictKeyFilePermissions(Path keyFile) throws Exception {
        FileStore fileStore = Files.getFileStore(keyFile);
        if (fileStore.supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(keyFile, PRIVATE_KEY_PERMISSIONS);
        }
    }

    private void requirePrivateKeyPermissions(Path keyFile) throws Exception {
        FileStore fileStore = Files.getFileStore(keyFile);
        if (fileStore.supportsFileAttributeView("posix")
                && !Files.getPosixFilePermissions(keyFile).equals(PRIVATE_KEY_PERMISSIONS)) {
            throw new IllegalStateException("RSA key file must be readable and writable only by its owner");
        }
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
        claims.put("username", username);  // 添加 username claim
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
                .setSubject(userId)  // 使用 userId 作为 subject
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
        claims.put("username", username);  // 添加 username claim
        claims.put("type", "refresh");
        claims.put("jti", UUID.randomUUID().toString());
        
        long issuedAtMs = System.currentTimeMillis();
        long expiresInMs = expires.getRefreshToken(); // 从配置文件读取
        
        claims.put("iss", token.getIssuer());

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userId)  // 使用 userId 作为 subject
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
            var claims = parseSignedToken(token).getBody();
            return "refresh".equals(claims.get("type", String.class))
                    && this.token.getIssuer().equals(claims.getIssuer())
                    && claims.getId() != null
                    && !claims.getId().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    public Jws<Claims> parseSignedToken(String tokenValue) {
        return Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(tokenValue);
    }

    /**
     * 从 Token 中提取用户名
     * 优先从 username claim 提取，如果不存在则从 subject 提取（兼容旧版 Token）
     */
    public String extractUsername(String token) {
        try {
            var claims = Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String username = claims.get("username", String.class);
            if (username == null) {
                username = claims.getSubject();
            }
            return username;
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract username from token", e);
        }
    }

    /**
     * 从 Token 中提取用户 ID
     * 可以从 userId claim 或 subject (sub) 中提取
     */
    public String getUserIdFromToken(String token) {
        try {
            var claims = Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            
            // 优先从 userId claim 获取，如果不存在则从 subject 获取
            String userId = claims.get("userId", String.class);
            if (userId == null) {
                userId = claims.getSubject();
            }
            return userId;
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract user ID from token", e);
        }
    }

    /**
     * 获取 JWT 解码器
     * 用于 OAuth2 资源服务器验证 JWT Token
     */
    public org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() {
        return jwtDecoder(jwt -> OAuth2TokenValidatorResult.success());
    }

    public org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder(
            OAuth2TokenValidator<Jwt> additionalValidator) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey((java.security.interfaces.RSAPublicKey) publicKey)
                        .build();

        OAuth2TokenValidator<Jwt> issuerValidator =
                JwtValidators.createDefaultWithIssuer(token.getIssuer());
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            if (jwt.getAudience() != null
                    && jwt.getAudience().contains(token.getAudience())) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "The required audience is missing",
                    null
            ));
        };
        OAuth2TokenValidator<Jwt> accessTypeValidator = jwt -> {
            if ("access".equals(jwt.getClaimAsString("type"))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Only access tokens are accepted",
                    null
            ));
        };
        OAuth2TokenValidator<Jwt> headerValidator = jwt -> {
            Object algorithm = jwt.getHeaders().get("alg");
            Object kid = jwt.getHeaders().get("kid");
            if ("RS256".equals(algorithm) && token.getKid().equals(kid)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Token header is invalid",
                    null
            ));
        };
        OAuth2TokenValidator<Jwt> identityValidator = jwt -> {
            String subject = jwt.getSubject();
            String userId = jwt.getClaimAsString("userId");
            String username = jwt.getClaimAsString("username");
            if (subject != null
                    && !subject.isBlank()
                    && subject.equals(userId)
                    && username != null
                    && !username.isBlank()) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Token identity claims are invalid",
                    null
            ));
        };
        OAuth2TokenValidator<Jwt> jtiValidator = jwt -> {
            if (jwt.getId() != null && !jwt.getId().isBlank()) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Token jti is missing",
                    null
            ));
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                audienceValidator,
                accessTypeValidator,
                headerValidator,
                identityValidator,
                jtiValidator,
                additionalValidator
        ));
        return decoder;
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
