package com.uniappweb.api;

import java.sql.SQLException;
import javax.sql.DataSource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供免会话的 GET /api/ready，用应用连接探测 MySQL 和 Redis。
 * 响应采用 status/mysql/redis 探针结构，供 H5 和运行检查使用。
 * @see "docs/decisions(决策).md#D3"
 */
@RestController
public class ReadinessController {

    // 给探针中的 SELECT 1 设置单独的查询等待上限。
    private static final int MYSQL_QUERY_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    /**
     * 注入应用运行时使用的连接工厂。
     *
     * @param dataSource MySQL 数据源
     * @param redisConnectionFactory Redis 连接工厂
     */
    public ReadinessController(DataSource dataSource, RedisConnectionFactory redisConnectionFactory) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    /**
     * 检查两个依赖并返回当前状态。
     *
     * @return 两项均可用时为 200，否则为 503，两种响应都包含各依赖状态
     * @see "docs/decisions(决策).md#D3"
     */
    @Operation(summary = "检查应用就绪状态",
            description = "使用应用账号执行 MySQL SELECT 1 和 Redis PING。两项成功返回 200，任一失败返回 503。")
    @GetMapping("/api/ready")
    public ResponseEntity<Readiness> ready() {
        boolean mysqlUp = checkMysql();
        boolean redisUp = checkRedis();
        boolean ready = mysqlUp && redisUp;
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new Readiness(ready ? "ready" : "not_ready",
                        mysqlUp ? "up" : "down", redisUp ? "up" : "down"));
    }

    private boolean checkMysql() {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(MYSQL_QUERY_TIMEOUT_SECONDS);
            try (var result = statement.executeQuery("SELECT 1")) {
                return result.next() && result.getInt(1) == 1;
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    private boolean checkRedis() {
        try (var connection = redisConnectionFactory.getConnection()) {
            return "PONG".equals(connection.ping());
        } catch (DataAccessException ignored) {
            return false;
        }
    }

    @Schema(description = "聚合及分项就绪状态，不包含连接配置")
    private record Readiness(
            @Schema(description = "总体状态", allowableValues = {"ready", "not_ready"}) String status,
            @Schema(description = "MySQL 状态", allowableValues = {"up", "down"}) String mysql,
            @Schema(description = "Redis 状态", allowableValues = {"up", "down"}) String redis) {
    }
}
