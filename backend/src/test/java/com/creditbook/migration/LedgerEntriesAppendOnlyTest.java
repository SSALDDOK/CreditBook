package com.creditbook.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.support.RequiresDocker;

/**
 * CB-7 / 정합성 증명 테스트 3종 중 "append-only 차단":
 * 애플리케이션 계정으로 ledger_entries 를 UPDATE·DELETE·TRUNCATE 하면 트리거가 거절하고,
 * 정정 수단인 반제 행 INSERT 는 계속 허용되는지 증명한다.
 */
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Tag("CB-7")
class LedgerEntriesAppendOnlyTest {

	/** PostgreSQL restrict_violation — fn_ledger_entries_append_only() 가 내는 코드 */
	private static final String RESTRICT_VIOLATION = "23001";

	@Autowired
	JdbcTemplate jdbcTemplate;

	private UUID employeeId;
	private UUID accountId;
	private UUID chargeId;

	@BeforeEach
	void setUp() {
		String loginId = "tester-" + UUID.randomUUID().toString().substring(0, 8);
		employeeId = jdbcTemplate.queryForObject(
				"INSERT INTO employees (login_id, password_hash, name, role) VALUES (?, ?, ?, 'ADMIN') RETURNING id",
				UUID.class, loginId, "hashed-password", "테스트 관리자");
		UUID customerId = jdbcTemplate.queryForObject(
				"INSERT INTO customers (name, phone) VALUES (?, ?) RETURNING id", UUID.class, "홍길동", "01012345678");
		accountId = jdbcTemplate.queryForObject(
				"INSERT INTO prepaid_accounts (customer_id, balance) VALUES (?, 5000) RETURNING id",
				UUID.class, customerId);
		chargeId = insertLedgerEntry("CHARGE", 5_000, 5_000, null);
	}

	@Test
	@DisplayName("애플리케이션 계정으로 ledger_entries 를 UPDATE 하면 실패한다")
	void update_on_ledger_entries_is_rejected() {
		// given: CHARGE 5,000원 원본 행

		// when / then
		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE ledger_entries SET amount = 1 WHERE id = ?", chargeId))
				.isInstanceOf(DataAccessException.class)
				.satisfies(ex -> assertThat(sqlStateOf(ex)).isEqualTo(RESTRICT_VIOLATION));
		assertThat(amountOf(chargeId)).isEqualByComparingTo(BigDecimal.valueOf(5_000));
	}

	@Test
	@DisplayName("애플리케이션 계정으로 ledger_entries 를 DELETE 하면 실패한다")
	void delete_on_ledger_entries_is_rejected() {
		// given: CHARGE 5,000원 원본 행

		// when / then
		assertThatThrownBy(() -> jdbcTemplate.update(
				"DELETE FROM ledger_entries WHERE id = ?", chargeId))
				.isInstanceOf(DataAccessException.class)
				.satisfies(ex -> assertThat(sqlStateOf(ex)).isEqualTo(RESTRICT_VIOLATION));
		assertThat(countOf(chargeId)).isEqualTo(1);
	}

	@Test
	@DisplayName("애플리케이션 계정으로 ledger_entries 를 TRUNCATE 하면 실패한다")
	void truncate_on_ledger_entries_is_rejected() {
		// given: CHARGE 5,000원 원본 행

		// when / then
		assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE ledger_entries"))
				.isInstanceOf(DataAccessException.class)
				.satisfies(ex -> assertThat(sqlStateOf(ex)).isEqualTo(RESTRICT_VIOLATION));
		assertThat(countOf(chargeId)).isEqualTo(1);
	}

	@Test
	@DisplayName("정정은 반제 행 INSERT 로만 한다 — CHARGE_CANCEL 반제 행은 저장된다")
	void reversal_insert_is_still_allowed() {
		// given: CHARGE 5,000원 원본 행

		// when
		UUID cancelId = insertLedgerEntry("CHARGE_CANCEL", 5_000, 0, chargeId);

		// then
		assertThat(countOf(cancelId)).isEqualTo(1);
		assertThat(countOf(chargeId)).isEqualTo(1);
	}

	private UUID insertLedgerEntry(String type, long amount, long balanceAfter, UUID reversesId) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO ledger_entries (account_id, type, amount, balance_after, reverses_id, performed_by) "
						+ "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
				UUID.class, accountId, type, BigDecimal.valueOf(amount), BigDecimal.valueOf(balanceAfter),
				reversesId, employeeId);
	}

	private BigDecimal amountOf(UUID entryId) {
		return jdbcTemplate.queryForObject(
				"SELECT amount FROM ledger_entries WHERE id = ?", BigDecimal.class, entryId);
	}

	private int countOf(UUID entryId) {
		return jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entries WHERE id = ?", Integer.class, entryId);
	}

	private static String sqlStateOf(Throwable thrown) {
		Throwable cause = thrown;
		while (cause != null && !(cause instanceof SQLException)) {
			cause = cause.getCause();
		}
		return cause instanceof SQLException sqlException ? sqlException.getSQLState() : null;
	}

}
