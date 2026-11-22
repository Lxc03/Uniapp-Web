package com.uniappweb.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 认证和打卡接口的统一响应，成功时包含业务数据，失败时提供机器可读的错误码。
 *
 * @param code OK 或约定的错误码
 * @param message 供界面显示的简短消息
 * @param data 成功数据或错误响应中的 null
 * @param <T> 当前接口的业务载荷类型
 * @see "docs/decisions(决策).md#D6"
 */
@Schema(description = "统一业务响应")
public record ApiResult<T>(
        @Schema(description = "成功码 OK 或约定的错误码", example = "OK") String code,
        @Schema(description = "人读消息", example = "查询成功") String message,
        @Schema(description = "成功数据，发生错误时为 null") T data) {

    /**
     * 为身份或业务响应设置 HTTP 状态和 no-store 缓存指令。
     *
     * @param status 契约规定的 HTTP 状态
     * @return 可附加响应头和响应体的构造器
     */
    public static ResponseEntity.BodyBuilder response(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore());
    }

    /**
     * 生成业务码为 OK 的响应。
     *
     * @param message 当前操作的成功消息
     * @param data 当前接口的成功载荷
     * @param <T> 成功载荷类型
     * @return 统一的成功响应体
     */
    public static <T> ApiResult<T> success(String message, T data) {
        return new ApiResult<>("OK", message, data);
    }
}
