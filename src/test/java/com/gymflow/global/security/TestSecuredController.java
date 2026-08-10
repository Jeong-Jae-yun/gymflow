package com.gymflow.global.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// test-only: verifies the JWT filter + @PreAuthorize end-to-end; lives under src/test, never shipped
@RestController
public class TestSecuredController {

    @GetMapping("/api/v1/test/authenticated")
    public String authenticatedOnly() {
        return "ok";
    }

    @GetMapping("/api/v1/test/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminOnly() {
        return "ok";
    }
}
