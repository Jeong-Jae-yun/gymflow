package com.gymflow.global.security.jwt;

import com.gymflow.domain.user.domain.enumtype.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String TEST_SECRET =
            Base64.getEncoder().encodeToString("test-jwt-secret-key-for-unit-test-must-be-long-enough".getBytes());

    private final JwtTokenProvider jwtTokenProvider =
            new JwtTokenProvider(new JwtProperties(TEST_SECRET, 3_600_000L));

    @Test
    @DisplayName("정상적으로 발급한 Access Token은 유효성 검증을 통과하고 Claim을 올바르게 추출한다")
    void createAccessToken_ShouldBeValidAndContainClaims() {
        // given
        Long userId = 1L;
        String email = "test@gymflow.com";
        UserRole role = UserRole.USER;

        // when
        String token = jwtTokenProvider.createAccessToken(userId, email, role);

        // then
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUserId(token)).isEqualTo(userId);
        assertThat(jwtTokenProvider.getEmail(token)).isEqualTo(email);
        assertThat(jwtTokenProvider.getRole(token)).isEqualTo(role);
    }

    @Test
    @DisplayName("만료된 Token은 검증에 실패한다")
    void validateToken_WithExpiredToken_ShouldReturnFalse() {
        // given
        JwtTokenProvider expiredTokenProvider = new JwtTokenProvider(new JwtProperties(TEST_SECRET, -1_000L));
        String expiredToken = expiredTokenProvider.createAccessToken(1L, "test@gymflow.com", UserRole.USER);

        // when & then
        assertThat(jwtTokenProvider.validateToken(expiredToken)).isFalse();
    }

    @Test
    @DisplayName("서명이 다른(위조된) Token은 검증에 실패한다")
    void validateToken_WithTamperedSignature_ShouldReturnFalse() {
        // given
        SecretKey otherKey = Keys.hmacShaKeyFor(
                Base64.getDecoder().decode(
                        Base64.getEncoder().encodeToString("another-completely-different-secret-key-value".getBytes())));
        String tokenSignedWithOtherKey = Jwts.builder()
                .subject("1")
                .claim("email", "test@gymflow.com")
                .claim("role", UserRole.USER.name())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(otherKey)
                .compact();

        // when & then
        assertThat(jwtTokenProvider.validateToken(tokenSignedWithOtherKey)).isFalse();
    }

    @Test
    @DisplayName("형식이 올바르지 않은 Token은 검증에 실패한다")
    void validateToken_WithMalformedToken_ShouldReturnFalse() {
        assertThat(jwtTokenProvider.validateToken("this-is-not-a-valid-jwt")).isFalse();
    }
}
