package com.gymflow.global.security.jwt;

import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.global.security.principal.CustomUserDetails;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JwtAuthenticationFilterTest {

    private static final String TEST_SECRET =
            Base64.getEncoder().encodeToString("test-jwt-secret-key-for-unit-test-must-be-long-enough".getBytes());

    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(new JwtProperties(TEST_SECRET, 3_600_000L));
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("유효한 Access Token이 있으면 SecurityContext에 인증 정보를 저장한다")
    void doFilter_WithValidToken_ShouldSetAuthentication() throws Exception {
        // given
        String token = jwtTokenProvider.createAccessToken(1L, "test@gymflow.com", UserRole.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when
        filter.doFilter(request, response, filterChain);

        // then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
        assertThat(principal.getId()).isEqualTo(1L);
        assertThat(principal.getUsername()).isEqualTo("test@gymflow.com");
        assertThat(principal.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 SecurityContext에 인증 정보를 저장하지 않고 다음 필터로 진행한다")
    void doFilter_WithNoToken_ShouldNotSetAuthentication() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when
        filter.doFilter(request, response, filterChain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("유효하지 않은 Token이면 SecurityContext에 인증 정보를 저장하지 않고 다음 필터로 진행한다")
    void doFilter_WithInvalidToken_ShouldNotSetAuthentication() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when
        filter.doFilter(request, response, filterChain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }
}
