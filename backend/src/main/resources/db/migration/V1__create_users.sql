-- [D4-A] 账号名在应用层归一，唯一索引保证并发下每个账号只存在一行。
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_name VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    CONSTRAINT ck_users_username_lowercase CHECK (username = LOWER(username))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;
