package com.creditbook.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.support.RequiresDocker;

/**
 * V2__customers_phone_not_null.sql 이 적용된 상태에서 연락처 없는 고객을 DB 가 거절하는지 증명한다.
 * API·도메인 검증을 거치지 않는 경로(직접 SQL)로도 NULL 이 들어가지 않아야 한다.
 */
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Tag("REQ-1")
class V2CustomersPhoneNotNullTest {

	/** PostgreSQL not_null_violation */
	private static final String NOT_NULL_VIOLATION = "23502";

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("연락처가 없으면 고객이 등록되지 않는다 (DB 제약)")
	void customer_without_phone_is_rejected() {
		// given: 연락처를 넣지 않는 직접 INSERT

		// when / then
		assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO customers (name) VALUES (?)", "연락처없음"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> {
					PSQLException psql = psqlExceptionOf(ex);
					assertThat(psql.getSQLState()).isEqualTo(NOT_NULL_VIOLATION);
					assertThat(psql.getServerErrorMessage().getColumn()).isEqualTo("phone");
				});
	}

	@Test
	@DisplayName("연락처가 있으면 고객이 등록된다")
	void customer_with_phone_is_saved() {
		// given
		String phone = "01012345678";

		// when
		UUID id = jdbcTemplate.queryForObject(
				"INSERT INTO customers (name, phone) VALUES (?, ?) RETURNING id", UUID.class, "연락처있음", phone);

		// then
		String saved = jdbcTemplate.queryForObject("SELECT phone FROM customers WHERE id = ?", String.class, id);
		assertThat(saved).isEqualTo(phone);
	}

	private static PSQLException psqlExceptionOf(Throwable thrown) {
		Throwable cause = thrown;
		while (cause != null && !(cause instanceof PSQLException)) {
			cause = cause.getCause();
		}
		assertThat(cause).isInstanceOf(PSQLException.class);
		return (PSQLException) cause;
	}
}
