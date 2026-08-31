package com.gymflow.global.security;

import com.gymflow.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CorsIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String DISALLOWED_ORIGIN = "https://evil-site.com";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("허용된 Origin의 요청에는 Access-Control-Allow-Origin 헤더가 포함된다")
    void request_WithAllowedOrigin_ShouldIncludeAllowOriginHeader() throws Exception {
        mockMvc.perform(get("/api/test/authenticated")
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    @Test
    @DisplayName("허용되지 않은 Origin의 요청에는 Access-Control-Allow-Origin 헤더가 없고 거부된다")
    void request_WithDisallowedOrigin_ShouldBeRejectedWithoutAllowOriginHeader() throws Exception {
        mockMvc.perform(get("/api/test/authenticated")
                        .header("Origin", DISALLOWED_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("회원가입 API의 CORS preflight 요청은 허용된 Origin에 대해 정상 처리된다")
    void signupPreflight_WithAllowedOrigin_ShouldReturnAllowHeaders() throws Exception {
        mockMvc.perform(options("/api/users/signup")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.containsString("POST")))
                .andExpect(header().exists("Access-Control-Allow-Headers"));
    }

    @Test
    @DisplayName("회원가입 API의 CORS preflight 요청은 허용되지 않은 Origin에 대해 거부된다")
    void signupPreflight_WithDisallowedOrigin_ShouldBeRejected() throws Exception {
        mockMvc.perform(options("/api/users/signup")
                        .header("Origin", DISALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
