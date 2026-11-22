package com.uniappweb.api.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.uniappweb.api.auth.AuthFailure.Kind.AUTH_INVALID_CREDENTIALS;
import static com.uniappweb.api.auth.AuthFailure.Kind.REQUEST_INVALID;
import static com.uniappweb.api.auth.AuthRequests.COOKIE_NAME;

/**
 * 提供登录、当前用户和 CSRF 令牌接口。用 MySQL 验证用户，用 Redis 保存会话，
 * 写请求检查 Origin，当前用户和令牌接口还要求有效 Cookie。响应统一为 ApiResult。
 *
 * @see "docs/decisions(决策).md#D4"
 * @see "docs/decisions(决策).md#D5"
 * @see "docs/decisions(决策).md#D6"
 * @see "docs/decisions(决策).md#D8"
 */
@RestController
@RequestMapping("/api/auth")
final class AuthController {

    @Schema(description = "登录及当前用户资料")
    private record UserData(@Schema(description = "当前用户的公开资料") AuthUsers.User user) {
    }

    @Schema(description = "当前会话绑定的写请求令牌")
    private record CsrfData(@Schema(description = "供后续写请求的 X-CSRF-Token 使用") String csrfToken) {
    }

    private final AuthUsers users;
    private final AuthPasswords passwords;
    private final AuthSessions sessions;
    private final AuthRequests requests;

    AuthController(AuthUsers users, AuthPasswords passwords, AuthSessions sessions, AuthRequests requests) {
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
        this.requests = requests;
    }

    /**
     * 校验凭据，在 Redis 创建固定有效期的会话后签发 Cookie。
     *
     * @param request 含可信 Origin 和用户名、密码 JSON 的请求
     * @return 200、用户资料及新签发的会话 Cookie
     * @throws AuthFailure 来源、媒体类型、参数、凭据或依赖校验失败
     * @see "docs/decisions(决策).md#D5"
     * @see "docs/decisions(决策).md#D6"
     */
    @Operation(summary = "登录",
            description = "校验来源和密码，Redis 保存会话后签发 HttpOnly Cookie。密码错误和用户不存在均返回 401。",
            parameters = @Parameter(name = "Origin", in = ParameterIn.HEADER, required = true,
                    description = "配置的可信 H5 来源"),
            requestBody = @RequestBody(required = true, content = @Content(mediaType = "application/json",
                    schema = @Schema(type = "object", requiredProperties = {"username", "password"},
                            example = "{\"username\":\"demo_a\",\"password\":\"<本地配置密码>\"}"))))
    @PostMapping("/login")
    public ResponseEntity<ApiResult<UserData>> login(@Parameter(hidden = true) HttpServletRequest request) {
        requests.requireOrigin(request);
        var input = requests.readLoginJson(request);
        String username = AuthPasswords.normalizeUsername(input.path("username").textValue());
        String password = input.path("password").textValue();
        if (username == null || !AuthPasswords.validLength(password, 1)) {
            throw new AuthFailure(REQUEST_INVALID);
        }

        AuthUsers.Account account = users.findByUsername(username).orElse(null);
        // [D4-A] 未找到用户也执行一次 Argon2id 校验，保持认证失败路径接近，见 docs/decisions(决策).md#D4。
        boolean matches = passwords.matches(password, account == null ? null : account.passwordHash());
        if (!matches || account == null) {
            throw new AuthFailure(AUTH_INVALID_CREDENTIALS);
        }

        String sid = sessions.create(account.user().id());
        return ApiResult.response(HttpStatus.OK)
                .header(HttpHeaders.SET_COOKIE, requests.cookie(sid).build().toString())
                .body(ApiResult.success("登录成功", new UserData(account.user())));
    }

    /**
     * 解析会话并读取当前用户资料。
     *
     * @param sid 浏览器随请求携带的会话 Cookie 值
     * @return 200 及当前用户资料，读取不会刷新会话时限
     * @throws AuthFailure 会话缺失、失效或依赖不可用
     * @see "docs/decisions(决策).md#D5"
     * @see "docs/decisions(决策).md#D6"
     */
    @Operation(summary = "读取当前用户", description = "通过现有 Cookie 恢复用户，保持原会话到期时刻。")
    @GetMapping("/me")
    public ResponseEntity<ApiResult<UserData>> me(
            @Parameter(name = COOKIE_NAME, in = ParameterIn.COOKIE, required = true, description = "会话 Cookie")
            @CookieValue(name = COOKIE_NAME, required = false) String sid) {
        var user = requests.currentUser(sid);
        return ApiResult.response(HttpStatus.OK).body(ApiResult.success("查询成功", new UserData(user)));
    }

    /**
     * 并发安全地读取或补齐现有会话的 CSRF 令牌。
     *
     * @param request 含可信 Origin 和空 JSON 对象的请求
     * @param sid 浏览器随请求携带的会话 Cookie 值
     * @return 200 及当前会话唯一的 CSRF 令牌，保留原到期时刻
     * @throws AuthFailure 来源、会话、请求体不合法或依赖不可用
     * @see "docs/decisions(决策).md#D8"
     */
    @Operation(summary = "领取写请求令牌",
            description = "以可信来源和有效会话领取令牌。并发领取复用同一令牌，保留原会话有效期。",
            parameters = @Parameter(name = "Origin", in = ParameterIn.HEADER, required = true,
                    description = "配置的可信 H5 来源"),
            requestBody = @RequestBody(required = true, content = @Content(mediaType = "application/json",
                    schema = @Schema(type = "object", example = "{}"))))
    @PostMapping("/csrf")
    public ResponseEntity<ApiResult<CsrfData>> csrf(@Parameter(hidden = true) HttpServletRequest request,
            @Parameter(name = COOKIE_NAME, in = ParameterIn.COOKIE, required = true, description = "会话 Cookie")
            @CookieValue(name = COOKIE_NAME, required = false) String sid) {
        requests.requireOrigin(request);
        var user = requests.currentUser(sid);
        if (!requests.readJson(request).isEmpty()) {
            throw AuthFailure.invalidInput();
        }
        String token = sessions.claimCsrf(sid, user.id());
        return ApiResult.response(HttpStatus.OK).body(ApiResult.success("获取成功", new CsrfData(token)));
    }
}
