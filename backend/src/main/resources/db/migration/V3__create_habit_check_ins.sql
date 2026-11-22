CREATE TABLE habit_check_ins (
    id BIGINT NOT NULL AUTO_INCREMENT,
    habit_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_check_ins_habit FOREIGN KEY (habit_id) REFERENCES habits (id) ON DELETE RESTRICT,
    UNIQUE KEY uk_check_ins_habit_date (habit_id, business_date)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;
