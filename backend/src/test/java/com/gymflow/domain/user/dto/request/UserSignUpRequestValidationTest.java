package com.gymflow.domain.user.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserSignUpRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("모든 필드가 유효하면 validation 오류가 없다")
    void validate_WithValidRequest_ShouldHaveNoViolations() {
        // given
        UserSignUpRequest request = new UserSignUpRequest("test@gymflow.com", "securePassword123", "John Doe");

        // when
        Set<ConstraintViolation<UserSignUpRequest>> violations = validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("email이 빈 값이거나 형식이 올바르지 않으면 validation 오류가 발생한다")
    void validate_WithBlankOrInvalidEmail_ShouldHaveViolations() {
        assertThat(validator.validate(new UserSignUpRequest("", "securePassword123", "John Doe"))).isNotEmpty();
        assertThat(validator.validate(new UserSignUpRequest("not-an-email", "securePassword123", "John Doe"))).isNotEmpty();
    }

    @Test
    @DisplayName("password가 빈 값이거나 길이 제한을 벗어나면 validation 오류가 발생한다")
    void validate_WithBlankOrInvalidPassword_ShouldHaveViolations() {
        assertThat(validator.validate(new UserSignUpRequest("test@gymflow.com", "", "John Doe"))).isNotEmpty();
        assertThat(validator.validate(new UserSignUpRequest("test@gymflow.com", "short1", "John Doe"))).isNotEmpty();
    }

    @Test
    @DisplayName("name이 빈 값이면 validation 오류가 발생한다")
    void validate_WithBlankName_ShouldHaveViolations() {
        assertThat(validator.validate(new UserSignUpRequest("test@gymflow.com", "securePassword123", ""))).isNotEmpty();
    }
}
