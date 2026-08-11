package com.gymflow.global.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INACTIVE_USER(HttpStatus.UNAUTHORIZED, "비활성화된 계정입니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 Resource입니다."),
    RESOURCE_NOT_ACTIVE(HttpStatus.CONFLICT, "예약할 수 없는 Resource 상태입니다."),
    RESERVATION_POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource에 설정된 예약 정책이 없습니다."),
    INVALID_RESERVATION_DURATION(HttpStatus.BAD_REQUEST, "예약 시간이 허용된 범위를 벗어났습니다."),
    RESERVATION_TIME_CONFLICT(HttpStatus.CONFLICT, "이미 예약된 시간과 겹칩니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 예약입니다."),
    RESERVATION_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 예약 상태입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
