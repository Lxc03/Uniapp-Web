package com.uniappweb.api.habits;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

import com.uniappweb.api.auth.ApiResult;
import com.uniappweb.api.auth.AuthFailure;
import com.uniappweb.api.auth.AuthRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.uniappweb.api.auth.AuthRequests.COOKIE_NAME;

/**
 * 提供打卡项创建、列表和每日打卡。Redis 验证会话，MySQL 确定归属。
 * POST 附加 Origin 与 CSRF 校验，业务结果封装为 ApiResult。
 *
 * @see "docs/decisions(决策).md#D7"
 * @see "docs/decisions(决策).md#D8"
 * @see "docs/decisions(决策).md#D9"
 * @see "docs/decisions(决策).md#D10"
 * @see "docs/decisions(决策).md#D11"
 */
@RestController
@RequestMapping("/api/habits")
final class HabitController {

    private static final Pattern EDGE_WHITESPACE = Pattern.compile("(?U)\\A\\s+|\\s+\\z");
    private static final Pattern HABIT_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern DATE = Pattern.compile("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}");

    @Schema(description = "已创建的打卡项")
    private record CreatedHabit(Habits.Habit habit) {
    }

    @Schema(description = "当前用户的打卡项与同一观察日的状态")
    private record HabitList(
            @Schema(description = "服务端观察日", example = "2026-11-10") LocalDate businessDate,
            List<CheckIns.Item> items) {
    }

    private final AuthRequests requests;
    private final Habits habits;
    private final CheckIns checkIns;
    private final Clock clock;

    HabitController(AuthRequests requests, Habits habits, CheckIns checkIns, Clock clock) {
        this.requests = requests;
        this.habits = habits;
        this.checkIns = checkIns;
        this.clock = clock;
    }

    /**
     * 按会话身份创建打卡项，数据库唯一索引裁决并发重名。
     *
     * @param request 含可信来源、CSRF 令牌和 name 字段的 JSON 请求
     * @param sid 浏览器携带的会话 Cookie 值
     * @return 201 及创建的打卡项
     * @throws AuthFailure 身份、请求、名称冲突或依赖校验失败
     * @see "docs/decisions(决策).md#D7"
     * @see "docs/decisions(决策).md#D8"
     */
    @Operation(summary = "创建打卡项",
            description = "按当前用户创建规范化名称。同一用户重名返回 409 HABIT_NAME_CONFLICT。",
            parameters = {
                @Parameter(name = "Origin", in = ParameterIn.HEADER, required = true, description = "可信 H5 来源"),
                @Parameter(name = "X-CSRF-Token", in = ParameterIn.HEADER, required = true, description = "会话绑定令牌")
            },
            requestBody = @RequestBody(required = true, content = @Content(mediaType = "application/json",
                    schema = @Schema(type = "object", requiredProperties = "name",
                            example = "{\"name\":\"阅读\"}"))))
    @PostMapping
    public ResponseEntity<ApiResult<CreatedHabit>> create(@Parameter(hidden = true) HttpServletRequest request,
            @Parameter(name = COOKIE_NAME, in = ParameterIn.COOKIE, required = true, description = "会话 Cookie")
            @CookieValue(name = COOKIE_NAME, required = false) String sid) {
        long userId = requests.writeUserId(request, sid);
        var input = requests.readJson(request);
        String name = input.path("name").textValue();
        if (input.size() != 1 || name == null || name.codePoints().anyMatch(HabitController::invalidCodePoint)) {
            throw AuthFailure.invalidInput();
        }
        // [D7-A] 先规范化名称，再交给二进制排序规则比较，见 docs/decisions(决策).md#D7。
        name = Normalizer.normalize(EDGE_WHITESPACE.matcher(name).replaceAll(""), Normalizer.Form.NFC);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 50) {
            throw AuthFailure.invalidInput();
        }
        return ApiResult.response(HttpStatus.CREATED)
                .body(ApiResult.success("创建成功", new CreatedHabit(habits.create(userId, name))));
    }

    /**
     * 读取当前用户的打卡项及同一观察日的状态。
     *
     * @param sid 浏览器携带的会话 Cookie 值
     * @return 200 及观察日和排序后的打卡项
     * @throws AuthFailure 会话缺失、会话失效或依赖不可用
     * @see "docs/decisions(决策).md#D8"
     * @see "docs/decisions(决策).md#D9"
     * @see "docs/decisions(决策).md#D11"
     */
    @Operation(summary = "查询打卡项列表",
            description = "返回当前用户的项、业务日期、今日状态和连续天数。")
    @GetMapping
    public ResponseEntity<ApiResult<HabitList>> list(
            @Parameter(name = COOKIE_NAME, in = ParameterIn.COOKIE, required = true, description = "会话 Cookie")
            @CookieValue(name = COOKIE_NAME, required = false) String sid) {
        LocalDate day = businessDate();
        long userId = requests.readUserId(sid);
        return ApiResult.response(HttpStatus.OK)
                .body(ApiResult.success("查询成功", new HabitList(day, checkIns.list(userId, day))));
    }

    /**
     * 查询当前用户指定项的当日状态。
     *
     * @param habitId 归当前用户所有的正十进制 BIGINT 项 ID
     * @param sid 浏览器携带的会话 Cookie 值
     * @return 200 及业务日期、今日状态和连续天数
     * @throws AuthFailure 会话、参数、归属校验失败或依赖不可用
     * @see "docs/decisions(决策).md#D9"
     * @see "docs/decisions(决策).md#D11"
     */
    @Operation(summary = "查询单项打卡状态",
            description = "查询当前观察日的状态。项不存在或归属其他用户时均返回 404。")
    @GetMapping("/{habitId}/check-ins/status")
    public ResponseEntity<ApiResult<CheckIns.Status>> status(
            @Parameter(description = "打卡项的正十进制 BIGINT ID", required = true) @PathVariable String habitId,
            @Parameter(name = COOKIE_NAME, in = ParameterIn.COOKIE, required = true, description = "会话 Cookie")
            @CookieValue(name = COOKIE_NAME, required = false) String sid) {
        LocalDate day = businessDate();
        long userId = requests.readUserId(sid);
        return ApiResult.response(HttpStatus.OK)
                .body(ApiResult.success("查询成功", checkIns.status(userId, parseHabitId(habitId), day)));
    }

    /**
     * 提交当前业务日的打卡，或按原日期核实已有记录。
     *
     * @param habitId 归当前用户所有的正十进制 BIGINT 项 ID
     * @param request 含可信来源、CSRF 令牌及 expectedBusinessDate 的请求
     * @param sid 浏览器携带的会话 Cookie 值
     * @return 首次写入为 201，读回已有记录为 200。同项同日并发最多写一条
     * @throws AuthFailure 身份、请求、归属、日期校验失败或依赖不可用
     * @see "docs/decisions(决策).md#D9"
     * @see "docs/decisions(决策).md#D10"
     * @see "docs/decisions(决策).md#D11"
     */
    @Operation(summary = "提交或核实每日打卡",
            description = "当前业务日允许提交。旧日期只读核实，未找到记录时返回 409。响应分别给出记录日与观察日。",
            parameters = {
                @Parameter(name = "Origin", in = ParameterIn.HEADER, required = true, description = "可信 H5 来源"),
                @Parameter(name = "X-CSRF-Token", in = ParameterIn.HEADER, required = true, description = "会话绑定令牌")
            },
            requestBody = @RequestBody(required = true, content = @Content(mediaType = "application/json",
                    schema = @Schema(type = "object", requiredProperties = "expectedBusinessDate",
                            example = "{\"expectedBusinessDate\":\"2026-11-09\"}"))))
    @PostMapping("/{habitId}/check-ins")
    public ResponseEntity<ApiResult<CheckIns.Outcome>> checkIn(
            @Parameter(description = "打卡项的正十进制 BIGINT ID", required = true) @PathVariable String habitId,
            @Parameter(hidden = true) HttpServletRequest request,
            @Parameter(name = COOKIE_NAME, in = ParameterIn.COOKIE, required = true, description = "会话 Cookie")
            @CookieValue(name = COOKIE_NAME, required = false) String sid) {
        LocalDate day = businessDate();
        long userId = requests.writeUserId(request, sid);
        var input = requests.readJson(request);
        String date = input.path("expectedBusinessDate").textValue();
        if (input.size() != 1 || date == null || !DATE.matcher(date).matches()) {
            throw AuthFailure.invalidInput();
        }
        LocalDate expected;
        try {
            expected = LocalDate.parse(date);
        } catch (DateTimeParseException ignored) {
            throw AuthFailure.invalidInput();
        }
        var outcome = checkIns.checkIn(userId, parseHabitId(habitId), day, expected);
        if ("CREATED".equals(outcome.outcome())) {
            return ApiResult.response(HttpStatus.CREATED).body(ApiResult.success("打卡成功", outcome));
        }
        return ApiResult.response(HttpStatus.OK).body(ApiResult.success("该日打卡已记录", outcome));
    }

    private LocalDate businessDate() {
        // [D9-A] 请求入口采样一次，后续等待 Redis、连接或锁都沿用该日，见 docs/decisions(决策).md#D9。
        return clock.instant().atZone(BusinessTime.ZONE).toLocalDate();
    }

    private static long parseHabitId(String value) {
        if (HABIT_ID.matcher(value).matches()) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                throw AuthFailure.invalidInput();
            }
        }
        throw AuthFailure.invalidInput();
    }

    private static boolean invalidCodePoint(int codePoint) {
        return Character.isISOControl(codePoint)
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
    }
}
