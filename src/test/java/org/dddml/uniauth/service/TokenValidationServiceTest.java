package org.dddml.uniauth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import org.dddml.uniauth.entity.TokenFamilyEntity;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.repository.TokenBlacklistRepository;
import org.dddml.uniauth.repository.TokenFamilyRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refresh-token decoding is the gate between a signed JWT and a persisted
 * session. Every malformed-claim branch must reject with the stable 401 class,
 * otherwise a crafted token could become a usable session.
 *
 * Strictness is relaxed because each case deliberately exercises only the
 * stubs along its own rejection path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenValidationServiceTest {

    private static final String ISSUER = "https://auth.example.com";
    private static final String KID = "key-1";
    private static final String USER_ID =
            "11111111-1111-1111-1111-111111111111";
    private static final String FAMILY_ID =
            "22222222-2222-2222-2222-222222222222";
    private static final String TOKEN = "token-value";

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private TokenBlacklistRepository tokenBlacklistRepository;

    @Mock
    private TokenFamilyRepository tokenFamilyRepository;

    @Mock
    private UserRepository userRepository;

    private TokenValidationService service;

    private JwtDecoder signedDecoder;
    private JwtDecoder activeDecoder;

    @BeforeEach
    void setUp() {
        JwtTokenService.TokenConfig config = new JwtTokenService.TokenConfig();
        config.setIssuer(ISSUER);
        config.setKid(KID);
        when(jwtTokenService.getToken()).thenReturn(config);
        signedDecoder = mock(JwtDecoder.class);
        activeDecoder = mock(JwtDecoder.class);
        when(jwtTokenService.jwtDecoder()).thenReturn(signedDecoder);
        when(jwtTokenService.jwtDecoder(any(OAuth2TokenValidator.class)))
                .thenReturn(activeDecoder);
        service = new TokenValidationService(
                jwtTokenService,
                tokenBlacklistRepository,
                tokenFamilyRepository,
                userRepository
        );
        service.initialize();
    }

    @Test
    void acceptsAValidRefreshToken() {
        Claims claims = validClaims();
        stubParsed("RS256", KID, claims);
        stubActiveSession();

        TokenValidationService.ValidatedToken token =
                service.decodeRefreshToken(TOKEN);

        assertThat(token.userId()).isEqualTo(USER_ID);
        assertThat(token.username()).isEqualTo("alice");
        assertThat(token.familyId()).isEqualTo(FAMILY_ID);
        assertThat(token.generation()).isEqualTo(1);
        assertThat(token.securityVersion()).isEqualTo(1);
    }

    @Test
    void rejectsATokenThatIsNotARefreshToken() {
        Claims claims = validClaims();
        claims.put("type", "access");
        stubParsed("RS256", KID, claims);

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Only refresh tokens are accepted");
    }

    @Test
    void rejectsAnUnsupportedAlgorithm() {
        stubParsed("HS256", KID, validClaims());

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token algorithm is invalid");
    }

    @Test
    void rejectsAMissingOrUnknownKeyId() {
        stubParsed("RS256", null, validClaims());
        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token kid is invalid");

        stubParsed("RS256", "other-key", validClaims());
        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token kid is invalid");
    }

    @Test
    void rejectsAForeignIssuer() {
        Claims claims = validClaims();
        claims.setIssuer("https://evil.example.com");
        stubParsed("RS256", KID, claims);

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Refresh token issuer is invalid");
    }

    @Test
    void rejectsAMissingJti() {
        Claims claims = validClaims();
        claims.setId(null);
        stubParsed("RS256", KID, claims);

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token jti is missing");
    }

    @Test
    void rejectsMismatchedSubjectAndUserId() {
        Claims claims = validClaims();
        claims.setSubject("33333333-3333-3333-3333-333333333333");
        stubParsed("RS256", KID, claims);

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token user identity is invalid");
    }

    @Test
    void rejectsAMissingUsername() {
        Claims claims = validClaims();
        claims.put("username", " ");
        stubParsed("RS256", KID, claims);

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token username is missing");
    }

    @Test
    void rejectsANonCanonicalFamilyId() {
        Claims claims = validClaims();
        // A UUID containing hex letters: UUID.fromString normalizes it to
        // lowercase, so the non-canonical spelling must be rejected.
        claims.put("sid", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA");
        stubParsed("RS256", KID, claims);

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token family identifier is invalid");
    }

    @Test
    void rejectsNegativeSessionClaims() {
        Claims claims = validClaims();
        claims.put("generation", -1);
        stubParsed("RS256", KID, claims);

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token session claims are invalid");
    }

    @Test
    void acceptsZeroGenerationSecurityVersionAndAuthTime() {
        // Zero is a legitimate value: the first token generation, a user that
        // never changed credentials, and "no recent authentication". A naive
        // `<= 0` guard would lock those users out.
        Claims claims = validClaims();
        claims.put("generation", 0);
        claims.put("ver", 0);
        claims.put("auth_time", 0);
        stubParsed("RS256", KID, claims);
        UserEntity user = activeUser();
        user.setTokenSecurityVersion(0);
        TokenFamilyEntity family = activeFamily();
        family.setCurrentGeneration(0);
        family.setSecurityVersion(0);
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(tokenFamilyRepository.findById(FAMILY_ID))
                .thenReturn(Optional.of(family));

        TokenValidationService.ValidatedToken token =
                service.decodeRefreshToken(TOKEN);

        assertThat(token.generation()).isZero();
        assertThat(token.securityVersion()).isZero();
        assertThat(token.authTime()).isNull();
    }

    @Test
    void rejectsMissingTimestamps() {
        Claims claims = validClaims();
        claims.setIssuedAt(null);
        claims.setExpiration(null);
        stubParsed("RS256", KID, claims);

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token timestamps are invalid");
    }

    @Test
    void rejectsARevokedToken() {
        Claims claims = validClaims();
        stubParsed("RS256", KID, claims);
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(true);

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token has been revoked");
    }

    @Test
    void rejectsATokenWhoseUserNoLongerExists() {
        Claims claims = validClaims();
        stubParsed("RS256", KID, claims);
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token user does not exist");
    }

    @Test
    void rejectsATokenWhoseFamilyNoLongerExists() {
        Claims claims = validClaims();
        stubParsed("RS256", KID, claims);
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(false);
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(activeUser()));
        when(tokenFamilyRepository.findById(FAMILY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token family does not exist");
    }

    @Test
    void rejectsADisabledUser() {
        Claims claims = validClaims();
        stubParsed("RS256", KID, claims);
        UserEntity user = activeUser();
        user.setEnabled(false);
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(tokenFamilyRepository.findById(FAMILY_ID))
                .thenReturn(Optional.of(activeFamily()));

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token session is inactive");
    }

    @Test
    void rejectsAUserRenamedSinceTheTokenWasIssued() {
        Claims claims = validClaims();
        stubParsed("RS256", KID, claims);
        UserEntity user = activeUser();
        user.setUsername("bob");
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(tokenFamilyRepository.findById(FAMILY_ID))
                .thenReturn(Optional.of(activeFamily()));

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token session is inactive");
    }

    @Test
    void rejectsASecurityVersionMismatch() {
        Claims claims = validClaims();
        stubParsed("RS256", KID, claims);
        UserEntity user = activeUser();
        user.setTokenSecurityVersion(2);
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(tokenFamilyRepository.findById(FAMILY_ID))
                .thenReturn(Optional.of(activeFamily()));

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token session is inactive");
    }

    @Test
    void rejectsAGenerationMismatchForExactRotationDecoding() {
        Claims claims = validClaims();
        stubParsed("RS256", KID, claims);
        TokenFamilyEntity family = activeFamily();
        family.setCurrentGeneration(2);
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(false);
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(activeUser()));
        when(tokenFamilyRepository.findById(FAMILY_ID))
                .thenReturn(Optional.of(family));

        assertThatThrownBy(() -> service.decodeRefreshToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token session is inactive");
    }

    @Test
    void rotationDecodingDoesNotRequireAnActiveSession() {
        // Rotation must be able to read a token after logout so it can revoke
        // the whole family; only the persisted generation CAS decides replay.
        Claims claims = validClaims();
        stubParsed("RS256", KID, claims);

        TokenValidationService.ValidatedToken token =
                service.decodeRefreshTokenForRotation(TOKEN);

        assertThat(token.userId()).isEqualTo(USER_ID);
        assertThat(token.generation()).isEqualTo(1);
    }

    @Test
    void introspectRejectsUnknownTokenTypes() {
        Claims claims = validClaims();
        claims.put("type", "id_token");
        stubParsed("RS256", KID, claims);

        assertThatThrownBy(() -> service.introspect(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Unsupported token type");
    }

    @Test
    void introspectReturnsTheRefreshTokenShape() {
        Claims claims = validClaims();
        stubParsed("RS256", KID, claims);
        stubActiveSession();

        TokenValidationService.IntrospectedToken token =
                service.introspect(TOKEN);

        assertThat(token.subject()).isEqualTo(USER_ID);
        assertThat(token.audience()).isNull();
        assertThat(token.type()).isEqualTo("refresh");
        assertThat(token.generation()).isEqualTo(1);
        assertThat(token.securityVersion()).isEqualTo(1);
    }

    @Test
    void revocationLookupSwallowsInvalidTokens() {
        when(jwtTokenService.parseSignedToken(anyString()))
                .thenThrow(new IllegalStateException("tampered"));

        assertThat(service.refreshTokenForRevocation(TOKEN)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Access-token path. Access tokens are normally decoded by the Spring
    // Resource Server, so their claim/session checks used to be protected only
    // indirectly by HTTP integration tests. These direct tests pin the
    // differences against the refresh path:
    //   * access decoding uses the active (session-aware) decoder, while
    //     revocation lookup deliberately uses the signed-only decoder;
    //   * access checks `currentGeneration >= token.generation` (a token
    //     survives a normal rotation), whereas refresh rotation requires an
    //     exact generation match;
    //   * access introspection returns the normalized audience and `access`
    //     type, while refresh introspection has no audience;
    //   * a failed session check is reported as a validator error, never as
    //     a thrown exception.
    // ------------------------------------------------------------------

    @Test
    void accessTokenDecodingUsesTheActiveDecoderAndReturnsTheUserId() {
        Jwt jwt = validAccessJwt();
        when(activeDecoder.decode(TOKEN)).thenReturn(jwt);
        stubActiveSession();

        assertThat(service.decodeAccessToken(TOKEN)).isSameAs(jwt);
        assertThat(service.getUserIdFromAccessToken(TOKEN))
                .isEqualTo(USER_ID);
        assertThat(service.validatedAccessToken(TOKEN).userId())
                .isEqualTo(USER_ID);
    }

    @Test
    void accessTokenRejectsAMissingJti() {
        Jwt jwt = accessJwtWithoutId();
        when(activeDecoder.decode(TOKEN)).thenReturn(jwt);

        assertThatThrownBy(() -> service.validatedAccessToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token jti is missing");
    }

    @Test
    void accessTokenRejectsAMismatchedSubjectAndUserId() {
        Instant now = Instant.now();
        Jwt tampered = Jwt.withTokenValue(TOKEN)
                .header("alg", "RS256")
                .header("kid", KID)
                .subject(USER_ID)
                .audience(List.of("resource-server"))
                .issuedAt(now.minus(1, ChronoUnit.MINUTES))
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("type", "access")
                .claim("userId", "33333333-3333-3333-3333-333333333333")
                .claim("username", "alice")
                .claim("sid", FAMILY_ID)
                .claim("generation", 1)
                .claim("ver", 1)
                .claim("auth_time", now.getEpochSecond())
                .claim("jti", "jti-1")
                .build();
        when(activeDecoder.decode(TOKEN)).thenReturn(tampered);

        assertThatThrownBy(() -> service.validatedAccessToken(TOKEN))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token user identity is invalid");
    }

    @Test
    void accessTokenSurvivesARotationThatRefreshRotationWouldReject() {
        // Access tokens are issued once and reused across refreshes, so the
        // family generation may have advanced beyond the token's generation.
        Jwt jwt = validAccessJwt();
        when(activeDecoder.decode(TOKEN)).thenReturn(jwt);
        TokenFamilyEntity family = activeFamily();
        family.setCurrentGeneration(3);
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(false);
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(activeUser()));
        when(tokenFamilyRepository.findById(FAMILY_ID))
                .thenReturn(Optional.of(family));

        assertThat(service.validatedAccessToken(TOKEN).generation())
                .isEqualTo(1);
    }

    @Test
    void accessTokenAcceptsZeroGenerationSecurityVersionAndAuthTime() {
        Jwt jwt = accessJwt(generation -> generation
                .claim("generation", 0)
                .claim("ver", 0)
                .claim("auth_time", 0));
        when(activeDecoder.decode(TOKEN)).thenReturn(jwt);
        UserEntity user = activeUser();
        user.setTokenSecurityVersion(0);
        TokenFamilyEntity family = activeFamily();
        family.setCurrentGeneration(0);
        family.setSecurityVersion(0);
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(tokenFamilyRepository.findById(FAMILY_ID))
                .thenReturn(Optional.of(family));

        TokenValidationService.ValidatedToken token =
                service.validatedAccessToken(TOKEN);

        assertThat(token.generation()).isZero();
        assertThat(token.securityVersion()).isZero();
        assertThat(token.authTime()).isNull();
    }

    @Test
    void accessTokenRevocationLookupUsesTheSignedDecoderOnly() {
        // Logout must be able to read an access token that is already revoked,
        // so it decodes with the signed-only decoder and never consults the
        // active session.
        when(signedDecoder.decode(TOKEN)).thenReturn(validAccessJwt());

        Optional<TokenValidationService.ValidatedToken> token =
                service.accessTokenForRevocation(TOKEN);

        assertThat(token).isPresent();
        assertThat(token.orElseThrow().userId()).isEqualTo(USER_ID);
        verify(activeDecoder, never()).decode(anyString());
    }

    @Test
    void accessTokenRevocationLookupSwallowsInvalidTokens() {
        when(signedDecoder.decode(TOKEN))
                .thenThrow(new JwtException("tampered"));

        assertThat(service.accessTokenForRevocation(TOKEN)).isEmpty();
    }

    @Test
    void introspectReturnsTheAccessTokenShapeWithItsAudience() {
        Claims claims = validClaims();
        claims.put("type", "access");
        stubParsed("RS256", KID, claims);
        Jwt jwt = validAccessJwt();
        when(activeDecoder.decode(TOKEN)).thenReturn(jwt);
        stubActiveSession();

        TokenValidationService.IntrospectedToken token =
                service.introspect(TOKEN);

        assertThat(token.subject()).isEqualTo(USER_ID);
        assertThat(token.audience()).isEqualTo("resource-server");
        assertThat(token.type()).isEqualTo("access");
        assertThat(token.generation()).isEqualTo(1);
    }

    @Test
    void accessTokenValidatorAcceptsAnActiveSession() {
        Jwt jwt = validAccessJwt();
        stubActiveSession();

        OAuth2TokenValidatorResult result =
                accessSessionValidator().validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void accessTokenValidatorReportsAnInactiveSessionAsAnError() {
        Jwt jwt = validAccessJwt();
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(true);

        OAuth2TokenValidatorResult result =
                accessSessionValidator().validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors())
                .extracting(OAuth2Error::getErrorCode)
                .containsExactly("invalid_token");
        assertThat(result.getErrors())
                .extracting(OAuth2Error::getDescription)
                .containsExactly("Token session is inactive");
    }

    @Test
    void accessTokenValidatorSwallowsClaimFailuresAsAnError() {
        Jwt jwt = accessJwtWithoutId();

        OAuth2TokenValidatorResult result =
                accessSessionValidator().validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors())
                .extracting(OAuth2Error::getDescription)
                .containsExactly("Token session is inactive");
    }

    @SuppressWarnings("unchecked")
    private OAuth2TokenValidator<Jwt> accessSessionValidator() {
        ArgumentCaptor<OAuth2TokenValidator<Jwt>> captor =
                ArgumentCaptor.forClass(OAuth2TokenValidator.class);
        verify(jwtTokenService).jwtDecoder(captor.capture());
        return captor.getValue();
    }

    private Jwt validAccessJwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue(TOKEN)
                .header("alg", "RS256")
                .header("kid", KID)
                .subject(USER_ID)
                .audience(List.of("resource-server"))
                .issuedAt(now.minus(1, ChronoUnit.MINUTES))
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("type", "access")
                .claim("userId", USER_ID)
                .claim("username", "alice")
                .claim("sid", FAMILY_ID)
                .claim("generation", 1)
                .claim("ver", 1)
                .claim("auth_time", now.getEpochSecond())
                .claim("jti", "jti-1")
                .build();
    }

    private Jwt accessJwt(Consumer<Jwt.Builder> overrides) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue(TOKEN)
                .header("alg", "RS256")
                .header("kid", KID)
                .subject(USER_ID)
                .audience(List.of("resource-server"))
                .issuedAt(now.minus(1, ChronoUnit.MINUTES))
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("type", "access")
                .claim("userId", USER_ID)
                .claim("username", "alice")
                .claim("sid", FAMILY_ID)
                .claim("generation", 1)
                .claim("ver", 1)
                .claim("auth_time", now.getEpochSecond())
                .claim("jti", "jti-1");
        overrides.accept(builder);
        return builder.build();
    }

    private Jwt accessJwtWithoutId() {
        Instant now = Instant.now();
        return Jwt.withTokenValue(TOKEN)
                .header("alg", "RS256")
                .header("kid", KID)
                .subject(USER_ID)
                .audience(List.of("resource-server"))
                .issuedAt(now.minus(1, ChronoUnit.MINUTES))
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("type", "access")
                .claim("userId", USER_ID)
                .claim("username", "alice")
                .claim("sid", FAMILY_ID)
                .claim("generation", 1)
                .claim("ver", 1)
                .claim("auth_time", now.getEpochSecond())
                .build();
    }

    private Claims validClaims() {
        Instant now = Instant.now();
        Claims claims = Jwts.claims();
        claims.setId("jti-1");
        claims.setSubject(USER_ID);
        claims.setIssuer(ISSUER);
        claims.setIssuedAt(Date.from(now.minus(1, ChronoUnit.MINUTES)));
        claims.setExpiration(Date.from(now.plus(7, ChronoUnit.DAYS)));
        claims.put("type", "refresh");
        claims.put("userId", USER_ID);
        claims.put("username", "alice");
        claims.put("sid", FAMILY_ID);
        claims.put("generation", 1);
        claims.put("ver", 1);
        claims.put("auth_time", now.getEpochSecond());
        return claims;
    }

    private void stubParsed(String algorithm, String kid, Claims claims) {
        Jws<Claims> parsed = mock(Jws.class);
        JwsHeader header = mock(JwsHeader.class);
        when(header.get("alg")).thenReturn(algorithm);
        when(header.get("kid")).thenReturn(kid);
        when(parsed.getHeader()).thenReturn(header);
        when(parsed.getBody()).thenReturn(claims);
        when(jwtTokenService.parseSignedToken(TOKEN)).thenReturn(parsed);
    }

    private void stubActiveSession() {
        when(tokenBlacklistRepository.existsByJti("jti-1")).thenReturn(false);
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(activeUser()));
        when(tokenFamilyRepository.findById(FAMILY_ID))
                .thenReturn(Optional.of(activeFamily()));
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setUsername("alice");
        user.setEnabled(true);
        user.setTokenSecurityVersion(1);
        return user;
    }

    private TokenFamilyEntity activeFamily() {
        return TokenFamilyEntity.builder()
                .id(FAMILY_ID)
                .userId(USER_ID)
                .securityVersion(1)
                .currentGeneration(1)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build();
    }
}
