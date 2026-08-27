package com.gymflow.global.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INACTIVE_USER(HttpStatus.UNAUTHORIZED, "비활성화된 계정입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 Resource입니다."),
    RESOURCE_NOT_ACTIVE(HttpStatus.CONFLICT, "예약할 수 없는 Resource 상태입니다."),
    RESERVATION_POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource에 설정된 예약 정책이 없습니다."),
    INVALID_RESERVATION_DURATION(HttpStatus.BAD_REQUEST, "예약 시간이 허용된 범위를 벗어났습니다."),
    RESERVATION_TIME_CONFLICT(HttpStatus.CONFLICT, "이미 예약된 시간과 겹칩니다."),
    RESERVATION_IN_PROGRESS(HttpStatus.CONFLICT, "해당 시간대의 예약 요청이 처리 중입니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 예약입니다."),
    RESERVATION_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 예약 상태입니다."),
    RESERVATION_NOT_EXTENDABLE(HttpStatus.CONFLICT, "연장할 수 없는 예약 상태입니다."),
    RESERVATION_EXTENSION_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "최대 연장 횟수를 초과했습니다."),
    RESERVATION_MAX_DURATION_EXCEEDED(HttpStatus.BAD_REQUEST, "예약의 최대 이용 시간을 초과했습니다."),
    RESERVATION_NOT_CHECKINABLE(HttpStatus.CONFLICT, "체크인할 수 없는 예약 상태입니다."),
    RESERVATION_NOT_CHECKOUTABLE(HttpStatus.CONFLICT, "체크아웃할 수 없는 예약 상태입니다."),
    FAVORITE_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 즐겨찾기에 추가된 Resource입니다."),
    FAVORITE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 즐겨찾기입니다."),
    INVALID_WAITING_QUEUE_TIME_RANGE(HttpStatus.BAD_REQUEST, "startAt은 endAt보다 이전이어야 합니다."),
    WAITING_QUEUE_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 동일한 시간대에 대기 중인 대기열이 있습니다."),
    WAITING_QUEUE_IN_PROGRESS(HttpStatus.CONFLICT, "동일한 대기열 등록 요청이 처리 중입니다."),
    WAITING_QUEUE_NOT_AVAILABLE(HttpStatus.CONFLICT, "이미 예약 가능한 시간대이므로 대기열에 등록할 수 없습니다."),
    WAITING_QUEUE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 대기열입니다."),
    WAITING_QUEUE_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 대기열 상태입니다."),
    PROMOTION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 승급 기회입니다."),
    PROMOTION_NOT_OFFERED(HttpStatus.CONFLICT, "이미 처리된 승급 기회입니다."),
    PROMOTION_EXPIRED(HttpStatus.CONFLICT, "승급 기회의 응답 시간이 만료되었습니다."),
    RESERVATION_PROMOTION_RESERVED(HttpStatus.CONFLICT, "해당 시간대는 대기열 승급 사용자에게 우선권이 부여되어 있습니다."),
    PROMOTION_CHECKIN_EXPIRED(HttpStatus.CONFLICT, "승급 예약의 체크인 가능 시간이 만료되었습니다."),
    INVALID_RESERVATION_POLICY(HttpStatus.BAD_REQUEST, "예약 정책 조합이 올바르지 않습니다."),
    RESOURCE_STATUS_CHANGE_NOT_ALLOWED(HttpStatus.CONFLICT, "활성 예약 또는 승급 제안이 존재하여 리소스 상태를 변경할 수 없습니다."),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "조회 시작 시각은 종료 시각보다 이전이어야 합니다."),
    INVALID_IMAGE_FILE(HttpStatus.BAD_REQUEST, "이미지 파일이 비어있습니다."),
    UNSUPPORTED_IMAGE_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 이미지 형식입니다. (JPEG, PNG, WebP만 허용)"),
    IMAGE_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "이미지 파일은 5MB를 초과할 수 없습니다."),
    RESOURCE_IMAGE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 업로드에 실패했습니다."),
    RESOURCE_IMAGE_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 삭제에 실패했습니다."),
    RESOURCE_IMAGE_URL_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 URL 생성에 실패했습니다.");

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
