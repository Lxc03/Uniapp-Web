package com.uniappweb.api.auth;

import org.springframework.http.HttpStatus;

/**
 * 身份、参数、业务规则及依赖失败时抛出的异常，由 ApiFailureHandler 转为公开错误。
 *
 * @see "docs/decisions(决策).md#D6"
 * @see "docs/decisions(决策).md#D8"
 * @see "docs/decisions(决策).md#D11"
 */
public final class AuthFailure extends RuntimeException {

    /** 对外响应中的业务码及对应 HTTP 状态。 */
    public enum Kind {
        /** 400，JSON 或请求字段未通过规则。 */
        REQUEST_INVALID(HttpStatus.BAD_REQUEST, "登录参数无效"),
        /** 401，用户名不存在或密码校验失败。 */
        AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "账号或密码错误"),
        /** 401，受保护的请求缺少会话 Cookie。 */
        AUTH_SESSION_REQUIRED(HttpStatus.UNAUTHORIZED, "请先登录"),
        /** 401，Cookie 格式错误、会话失效或用户已不存在，并清除 Cookie。 */
        AUTH_SESSION_INVALID_OR_EXPIRED(HttpStatus.UNAUTHORIZED, "会话无效或已过期，请重新登录"),
        /** 403，写请求的 Origin 缺失、重复或与配置不符。 */
        AUTH_ORIGIN_REJECTED(HttpStatus.FORBIDDEN, "请求来源不受信任"),
        /** 403，写请求未提供令牌，或当前会话尚未领取令牌。 */
        AUTH_CSRF_REQUIRED(HttpStatus.FORBIDDEN, "请先获取写请求令牌"),
        /** 403，写请求令牌重复或不匹配当前会话。 */
        AUTH_CSRF_INVALID(HttpStatus.FORBIDDEN, "写请求令牌无效"),
        /** 415，要求 JSON 的端点收到其他媒体类型或无效类型。 */
        REQUEST_UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请使用 JSON 提交"),
        /** 409，同一用户的规范化打卡项名称命中唯一约束。 */
        HABIT_NAME_CONFLICT(HttpStatus.CONFLICT, "已有同名打卡项，请修改名称"),
        /** 404，指定项不存在或不归当前用户。 */
        HABIT_NOT_FOUND(HttpStatus.NOT_FOUND, "打卡项不存在"),
        /** 409，预期日期在未来，或过去日期尚未查到已有记录。 */
        CHECKIN_BUSINESS_DATE_CHANGED(HttpStatus.CONFLICT, "业务日期已变化，请刷新后确认"),
        /** 503，会话存储操作失败或记录不可读。保留浏览器 Cookie。 */
        AUTH_REDIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "登录服务暂不可用，请重试"),
        /** 503，登录或会话确认所需的用户表查询失败。 */
        AUTH_DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "用户服务暂不可用，请重试"),
        /** 503，打卡项创建或列表查询失败。 */
        HABIT_DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "打卡项服务暂不可用，请重试"),
        /** 503，打卡写入或读回结果失败，客户端可保留原日期再次核实。 */
        CHECKIN_DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "打卡结果暂无法核实，请重试");

        final HttpStatus status;
        final String message;

        Kind(HttpStatus status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    final Kind kind;

    /**
     * 构造使用契约默认消息的失败。
     *
     * @param kind 对外的错误码及 HTTP 状态
     */
    public AuthFailure(Kind kind) {
        this(kind, kind.message);
    }

    private AuthFailure(Kind kind, String publicMessage) {
        super(publicMessage);
        this.kind = kind;
    }

    /**
     * 构造通用输入失败，适用于非登录请求。
     *
     * @return 业务码为 REQUEST_INVALID 的 400 响应异常
     */
    public static AuthFailure invalidInput() {
        return new AuthFailure(Kind.REQUEST_INVALID, "请求参数无效");
    }
}
