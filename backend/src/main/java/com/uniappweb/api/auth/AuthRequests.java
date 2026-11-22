package com.uniappweb.api.auth;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import static com.uniappweb.api.auth.AuthFailure.Kind.*;

/**
 * 为控制器集中处理 Origin、JSON、会话 Cookie 和 CSRF。用户身份由 Redis 会话和 MySQL 用户资料共同确认。
 *
 * @see "docs/decisions(决策).md#D5"
 * @see "docs/decisions(决策).md#D8"
 */
@Component
public final class AuthRequests {

    /** 浏览器存放不透明会话 ID 的 Cookie 名。 */
    public static final String COOKIE_NAME = "uniapp_sid";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    // 含 Unicode 转义的合法凭据和名称也能容纳在 4 KiB 请求体内，超限统一返回输入错误。
    private static final int MAX_BODY_BYTES = 4096;

    private final AuthUsers users;
    private final AuthSessions sessions;
    private final ObjectReader jsonReader;
    private final String publicOrigin;
    private final boolean secureCookie;

    AuthRequests(AuthUsers users, AuthSessions sessions, ObjectMapper mapper,
                 @Value("${app.auth.public-origin}") String publicOrigin,
                 @Value("${app.auth.cookie-secure}") boolean secureCookie) {
        validateOriginConfiguration(publicOrigin, secureCookie);
        this.users = users;
        this.sessions = sessions;
        this.publicOrigin = publicOrigin;
        this.secureCookie = secureCookie;
        this.jsonReader = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    /**
     * 验证写请求中的唯一 Origin 与服务端配置完全一致。
     *
     * @param request 需要验证来源的 HTTP 请求
     * @throws AuthFailure 来源缺失、重复或不同
     * @see "docs/decisions(决策).md#D5"
     */
    public void requireOrigin(HttpServletRequest request) {
        if (!Collections.list(request.getHeaders(HttpHeaders.ORIGIN)).equals(List.of(publicOrigin))) {
            throw new AuthFailure(AUTH_ORIGIN_REJECTED);
        }
    }

    AuthUsers.User currentUser(String sid) {
        return userFor(session(sid));
    }

    /**
     * 验证写请求并从当前会话取得用户 ID。
     *
     * @param request 含 Origin 和单个 CSRF 请求头的请求
     * @param sid 来自会话 Cookie 的原始 ID，可为空
     * @return 已通过来源、会话、令牌及用户校验的用户 ID
     * @throws AuthFailure 来源、会话、令牌校验失败或依赖不可用
     * @see "docs/decisions(决策).md#D8"
     */
    public long writeUserId(HttpServletRequest request, String sid) {
        requireOrigin(request);
        AuthSessions.Session session = session(sid);
        List<String> tokens = Collections.list(request.getHeaders(CSRF_HEADER));
        if (tokens.isEmpty() || tokens.getFirst().isBlank() || session.csrfToken() == null) {
            throw new AuthFailure(AUTH_CSRF_REQUIRED);
        }
        // [D8-A] 用定时安全的摘要比较方法验证令牌，见 docs/decisions(决策).md#D8。
        if (tokens.size() != 1 || !MessageDigest.isEqual(session.csrfToken().getBytes(StandardCharsets.UTF_8),
                tokens.getFirst().getBytes(StandardCharsets.UTF_8))) {
            throw new AuthFailure(AUTH_CSRF_INVALID);
        }
        return Long.parseLong(userFor(session).id());
    }

    /**
     * 验证只读接口的会话并返回当前用户 ID。
     *
     * @param sid 来自会话 Cookie 的原始 ID，可为空
     * @return 已验证的用户 ID
     * @throws AuthFailure 会话缺失、失效或依赖不可用
     * @see "docs/decisions(决策).md#D5"
     */
    public long readUserId(String sid) {
        return Long.parseLong(currentUser(sid).id());
    }

    private AuthSessions.Session session(String sid) {
        if (sid == null) {
            throw new AuthFailure(AUTH_SESSION_REQUIRED);
        }
        return sessions.find(sid).orElseThrow(() -> new AuthFailure(AUTH_SESSION_INVALID_OR_EXPIRED));
    }

    private AuthUsers.User userFor(AuthSessions.Session session) {
        return users.findById(session.userId())
                .orElseThrow(() -> new AuthFailure(AUTH_SESSION_INVALID_OR_EXPIRED));
    }

    ResponseCookie.ResponseCookieBuilder cookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value).path("/api")
                .httpOnly(true).sameSite("Lax").secure(secureCookie);
    }

    /**
     * 在体积限制内读取单个 JSON 对象，拒绝重复键与尾随内容。
     *
     * @param request 含 JSON Content-Type 和请求体的 HTTP 请求
     * @return 已解析的 JSON 对象
     * @throws AuthFailure 媒体类型错误、请求过大或 JSON 解析失败
     * @see "docs/decisions(决策).md#D6"
     */
    public JsonNode readJson(HttpServletRequest request) {
        return readJson(request, false);
    }

    JsonNode readLoginJson(HttpServletRequest request) {
        return readJson(request, true);
    }

    private JsonNode readJson(HttpServletRequest request, boolean login) {
        requireJson(request.getContentType());
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length <= MAX_BODY_BYTES) {
                JsonNode input = jsonReader.readTree(body);
                if (input != null && input.isObject()) {
                    return input;
                }
            }
        } catch (IOException ignored) {
            // 解析异常可能包含请求体，响应只使用约定的业务错误。
        }
        throw (login ? new AuthFailure(REQUEST_INVALID) : AuthFailure.invalidInput());
    }

    private static void requireJson(String contentType) {
        try {
            if (contentType != null && MediaType.APPLICATION_JSON.includes(MediaType.parseMediaType(contentType))) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // 无法解析的媒体类型按同一条 415 契约处理。
        }
        throw new AuthFailure(REQUEST_UNSUPPORTED_MEDIA_TYPE);
    }

    private static void validateOriginConfiguration(String origin, boolean secureCookie) {
        URI url = URI.create(origin);
        String host = url.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        boolean protocolAllowed = secureCookie ? "https".equals(url.getScheme())
                : loopback && "http".equals(url.getScheme());
        if (!protocolAllowed || host == null || !url.getRawPath().isEmpty()
                || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw new IllegalArgumentException("Configure an HTTPS origin with Secure cookies or a local HTTP origin.");
        }
    }
}
