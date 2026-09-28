package com.creditbook.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.support.RequiresDocker;

/**
 * CB-5: V1__init.sql 이 실제 PostgreSQL(Testcontainers)에 적용된 상태에서
 * ledger_entries 의 반제(reverses) 관련 CHECK/부분 유니크 제약이 스키마 정의서대로
 * 동작하는지 증명한다. 도메인 레벨 검증(반제 유형 대응 등)은 별도 도메인 테스트의 몫이며
 * 여기서는 DB 제약만 다룬다.
 */
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Tag("CB-5")
class V1SchemaConstraintsTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	private UUID employeeId;
	private UUID accountId;

	@BeforeEach
	void setUp() {
		String loginId = "tester-" + UUID.randomUUID().toString().substring(0, 8);
		employeeId = jdbcTemplate.queryForObject(
				"INSERT INTO employees (login_id, password_hash, name, role) VALUES (?, ?, ?, 'ADMIN') RETURNING id",
				UUID.class, loginId, "hashed-password", "테스트 관리자");

		UUID customerId = jdbcTemplate.queryForObject(
				"INSERT INTO customers (name) VALUES (?) RETURNING id",
				UUID.class, "홍길동");

		accountId = jdbcTemplate.queryForObject(
				"INSERT INTO prepaid_accounts (customer_id, balance) VALUES (?, 10000) RETURNING id",
				UUID.class, customerId);
	}

	@ParameterizedTest(name = "{0} 행은 reverses_id 없이 저장되지 않는다")
	@ValueSource(strings = { "USE_CANCEL", "CHARGE_CANCEL" })
	@DisplayName("USE_CANCEL/CHARGE_CANCEL 행은 reverses_id 없이 저장되지 않는다")
	void cancel_entry_without_reverses_id_is_rejected(String cancelType) {
		// given: 반제 유형이지만 reverses_id 를 지정하지 않는다
		// when / then
		assertThatThrownBy(() -> insertLedgerEntry(cancelType, 1_000, 9_000, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> assertThat(constraintNameOf(ex)).isEqualTo("ck_ledger_entries_reverses"));
	}

	@ParameterizedTest(name = "{0} 행은 reverses_id 를 가질 수 없다")
	@ValueSource(strings = { "CHARGE", "USE" })
	@DisplayName("CHARGE/USE 행은 reverses_id 를 가질 수 없다")
	void original_entry_with_reverses_id_is_rejected(String originalType) {
		// given: 반제 대상으로 삼을 유효한 기존 행 하나
		UUID existingEntryId = insertLedgerEntry("CHARGE", 5_000, 15_000, null);

		// when / then: CHARGE/USE 행에 reverses_id 를 채워 넣는다
		assertThatThrownBy(() -> insertLedgerEntry(originalType, 1_000, 9_000, existingEntryId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> assertThat(constraintNameOf(ex)).isEqualTo("ck_ledger_entries_reverses"));
	}

	@Test
	@DisplayName("CHARGE 행의 signed_amount 는 amount 와 같은 양수로 저장된다")
	void charge_entry_has_positive_signed_amount() {
		// given: CHARGE 10,000원
		// when
		UUID id = insertLedgerEntry("CHARGE", 10_000, 20_000, null);

		// then
		BigDecimal signedAmount = signedAmountOf(id);
		assertThat(signedAmount).isEqualByComparingTo(BigDecimal.valueOf(10_000));
	}

	@Test
	@DisplayName("USE 행의 signed_amount 는 -amount 로 저장된다")
	void use_entry_has_negative_signed_amount() {
		// given: USE 3,000원
		// when
		UUID id = insertLedgerEntry("USE", 3_000, 7_000, null);

		// then
		BigDecimal signedAmount = signedAmountOf(id);
		assertThat(signedAmount).isEqualByComparingTo(BigDecimal.valueOf(-3_000));
	}

	@Test
	@DisplayName("CHARGE_CANCEL 행은 올바른 reverses_id 와 함께 저장되고 signed_amount 는 -amount 이다")
	void charge_cancel_entry_with_valid_reverses_id_is_saved_with_negative_signed_amount() {
		// given: 취소 대상이 될 CHARGE 원본 행
		UUID originalChargeId = insertLedgerEntry("CHARGE", 5_000, 15_000, null);

		// when: 같은 계좌에 CHARGE_CANCEL 행을 원본을 가리키며 저장한다
		UUID cancelId = insertLedgerEntry("CHARGE_CANCEL", 5_000, 10_000, originalChargeId);

		// then: 저장에 성공하고 signed_amount 는 음수다 (잔액에서 차감되므로)
		assertThat(cancelId).isNotNull();
		assertThat(signedAmountOf(cancelId)).isEqualByComparingTo(BigDecimal.valueOf(-5_000));
	}

	@Test
	@DisplayName("USE_CANCEL 행은 올바른 reverses_id 와 함께 저장되고 signed_amount 는 +amount 이다")
	void use_cancel_entry_with_valid_reverses_id_is_saved_with_positive_signed_amount() {
		// given: 취소 대상이 될 USE 원본 행
		UUID originalUseId = insertLedgerEntry("USE", 3_000, 7_000, null);

		// when: 같은 계좌에 USE_CANCEL 행을 원본을 가리키며 저장한다 (잔액 복구이므로 +)
		UUID cancelId = insertLedgerEntry("USE_CANCEL", 3_000, 10_000, originalUseId);

		// then: 저장에 성공하고 signed_amount 는 양수다
		assertThat(cancelId).isNotNull();
		assertThat(signedAmountOf(cancelId)).isEqualByComparingTo(BigDecimal.valueOf(3_000));
	}

	@Test
	@DisplayName("같은 원본 거래를 두 번 반제할 수 없다")
	void same_original_entry_cannot_be_reversed_twice() {
		// given: CHARGE 원본과 그에 대한 첫 번째 CHARGE_CANCEL 반제(성공)
		UUID originalChargeId = insertLedgerEntry("CHARGE", 5_000, 15_000, null);
		insertLedgerEntry("CHARGE_CANCEL", 5_000, 10_000, originalChargeId);

		// when / then: 같은 원본을 다시 반제 시도
		assertThatThrownBy(() -> insertLedgerEntry("CHARGE_CANCEL", 5_000, 5_000, originalChargeId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> assertThat(constraintNameOf(ex)).isEqualTo("ux_ledger_entries_reverses"));
	}

	@ParameterizedTest(name = "amount={0} 인 행은 거절된다")
	@ValueSource(longs = { 0, -1, -100, -300_000 })
	@DisplayName("amount 가 0 이하이면 거절된다")
	void non_positive_amount_is_rejected(long amount) {
		// given / when / then
		assertThatThrownBy(() -> insertLedgerEntry("CHARGE", amount, 10_000, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> assertThat(constraintNameOf(ex)).isEqualTo("ck_ledger_entries_amount"));
	}

	@Test
	@DisplayName("같은 로그인 ID로 직원을 두 번 만들 수 없다")
	void duplicate_login_id_is_rejected() {
		// given: 같은 로그인 ID 의 직원이 이미 있다
		String loginId = "dup-" + UUID.randomUUID().toString().substring(0, 8);
		insertEmployee(loginId);

		// when / then
		assertThatThrownBy(() -> insertEmployee(loginId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> assertThat(constraintNameOf(ex)).isEqualTo("ux_employees_login_id"));
	}

	@Test
	@DisplayName("고객 1명에게 선결제 계좌를 두 개 만들 수 없다")
	void second_account_for_same_customer_is_rejected() {
		// given: 계좌가 이미 있는 고객
		UUID customerId = jdbcTemplate.queryForObject(
				"SELECT customer_id FROM prepaid_accounts WHERE id = ?", UUID.class, accountId);

		// when / then
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO prepaid_accounts (customer_id) VALUES (?)", customerId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> assertThat(constraintNameOf(ex)).isEqualTo("ux_prepaid_accounts_customer_id"));
	}

	@Test
	@DisplayName("거래 순번(seq)은 중복될 수 없다")
	void duplicate_seq_is_rejected() {
		// given: 이미 저장된 거래의 seq
		UUID entryId = insertLedgerEntry("CHARGE", 1_000, 11_000, null);
		Long seq = jdbcTemplate.queryForObject("SELECT seq FROM ledger_entries WHERE id = ?", Long.class, entryId);

		// when / then: 같은 seq 를 직접 지정해 넣는다
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO ledger_entries (seq, account_id, type, amount, balance_after, performed_by) "
						+ "VALUES (?, ?, 'CHARGE', 1000, 12000, ?)", seq, accountId, employeeId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> assertThat(constraintNameOf(ex)).isEqualTo("ux_ledger_entries_seq"));
	}

	private void insertEmployee(String loginId) {
		jdbcTemplate.update(
				"INSERT INTO employees (login_id, password_hash, name, role) VALUES (?, 'hashed-password', '직원', 'STAFF')",
				loginId);
	}

	private UUID insertLedgerEntry(String type, long amount, long balanceAfter, UUID reversesId) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO ledger_entries (account_id, type, amount, balance_after, reverses_id, performed_by) "
						+ "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
				UUID.class, accountId, type, BigDecimal.valueOf(amount), BigDecimal.valueOf(balanceAfter),
				reversesId, employeeId);
	}

	private BigDecimal signedAmountOf(UUID entryId) {
		return jdbcTemplate.queryForObject(
				"SELECT signed_amount FROM ledger_entries WHERE id = ?", BigDecimal.class, entryId);
	}

	private static String constraintNameOf(Throwable thrown) {
		Throwable cause = thrown;
		while (cause != null && !(cause instanceof PSQLException)) {
			cause = cause.getCause();
		}
		if (cause instanceof PSQLException psqlException && psqlException.getServerErrorMessage() != null) {
			return psqlException.getServerErrorMessage().getConstraint();
		}
		return null;
	}

}
