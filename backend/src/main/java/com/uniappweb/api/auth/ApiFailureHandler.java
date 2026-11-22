package com.uniappweb.api.auth;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.uniappweb.api.auth.AuthFailure.Kind.AUTH_SESSION_INVALID_OR_EXPIRED;

/**
 * 按 D6 将 AuthFailure 映射到统一响应，由 AuthRequests 处理旧 Cookie 的清理。
 *
 * @see "docs/decisions(决策).md#D5"
 * @see "docs/decisions(决策).md#D6"
 */
@RestControllerAdvice
final class ApiFailureHandler {

    private final AuthRequests requests;

    ApiFailureHandler(AuthRequests requests) {
        this.requests = requests;
    }

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<ApiResult<Void>> handleFailure(AuthFailure failure) {
        var response = ApiResult.response(failure.kind.status);
        if (failure.kind == AUTH_SESSION_INVALID_OR_EXPIRED) {
            // [D5-A] 只有确认失效才清除 Cookie，依赖故障允许重试，见 docs/decisions(决策).md#D5。
            response.header(HttpHeaders.SET_COOKIE, requests.cookie("").maxAge(Duration.ZERO).build().toString());
        }
        return response.body(new ApiResult<>(failure.kind.name(), failure.getMessage(), null));
    }
}
