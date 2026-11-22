CREATE TABLE habits (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_habits_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    UNIQUE KEY uk_habits_user_name (user_id, name),
    KEY idx_habits_user_created (user_id, created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;
