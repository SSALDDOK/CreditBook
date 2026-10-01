package com.creditbook.prepaid.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.support.RequiresDocker;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 도메인 엔티티 매핑이 V1 스키마와 맞는지 실제 PostgreSQL 로 확인한다.
 * (ddl-auto: validate 는 컨텍스트 로드 때 이미 돌고, 여기서는 DB 가 채우는 seq·signed_amount 와 @Version 을 본다)
 * 테스트 트랜잭션은 끝나면 롤백된다.
 */
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Tag("CB-30")
class PrepaidJpaMappingTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@PersistenceContext
	EntityManager em;

	private static final ChargePolicy POLICY = ChargePolicy.ofMaxAmount(300_000);

	private UUID employeeId;
	private UUID customerId;

	@BeforeEach
	void setUp() {
		String loginId = "mapper-" + UUID.randomUUID().toString().substring(0, 8);
		employeeId = jdbcTemplate.queryForObject(
				"INSERT INTO employees (login_id, password_hash, name, role) VALUES (?, ?, ?, 'STAFF') RETURNING id",
				UUID.class, loginId, "hashed-password", "매핑 테스트 직원");
		customerId = jdbcTemplate.queryForObject(
				"INSERT INTO customers (name, phone) VALUES (?, ?) RETURNING id", UUID.class, "매핑고객", "01012345678");
	}

	@Test
	@DisplayName("계좌와 거래를 저장하면 DB 가 seq 와 signed_amount 를 채우고 잔액·balance_after 가 그대로 저장된다")
	void persisted_entries_read_back_db_generated_columns() {
		// given
		Instant now = Instant.parse("2026-09-30T01:00:00Z");
		PrepaidAccount account = PrepaidAccount.open(customerId, now);
		em.persist(account);
		LedgerEntry charge = account.charge(BigDecimal.valueOf(50_000), POLICY, employeeId, now, "음료");
		em.persist(charge);
		LedgerEntry use = account.use(BigDecimal.valueOf(4_500), employeeId, now.plusSeconds(60), null);
		em.persist(use);

		// when
		em.flush();
		em.clear();
		PrepaidAccount reloadedAccount = em.find(PrepaidAccount.class, account.getId());
		LedgerEntry reloadedCharge = em.find(LedgerEntry.class, charge.getId());
		LedgerEntry reloadedUse = em.find(LedgerEntry.class, use.getId());

		// then
		assertThat(reloadedAccount.getBalance()).isEqualByComparingTo("45500");
		assertThat(reloadedAccount.getVersion()).isNotNull();
		assertThat(reloadedCharge.getSeq()).isNotNull();
		assertThat(reloadedUse.getSeq()).isGreaterThan(reloadedCharge.getSeq());
		assertThat(reloadedCharge.getSignedAmount()).isEqualByComparingTo("50000");
		assertThat(reloadedUse.getSignedAmount()).isEqualByComparingTo("-4500");
		assertThat(reloadedUse.getBalanceAfter()).isEqualByComparingTo("45500");
		assertThat(reloadedUse.getType()).isEqualTo(LedgerEntryType.USE);
		assertThat(reloadedUse.getPerformedBy()).isEqualTo(employeeId);
		assertThat(reloadedUse.getPerformedAt()).isEqualTo(now.plusSeconds(60));
		String storedType = jdbcTemplate.queryForObject(
				"SELECT type FROM ledger_entries WHERE id = ?", String.class, use.getId());
		assertThat(storedType).isEqualTo("USE");
	}

	@Test
	@Tag("REQ-34")
	@DisplayName("사용 취소 거래는 reverses_id 와 양수 signed_amount 로 저장된다")
	void use_cancel_is_persisted_as_reversal() {
		// given
		Instant now = Instant.parse("2026-09-30T01:00:00Z");
		PrepaidAccount account = PrepaidAccount.open(customerId, now);
		em.persist(account);
		em.persist(account.charge(BigDecimal.valueOf(10_000), POLICY, employeeId, now, null));
		LedgerEntry use = account.use(BigDecimal.valueOf(3_000), employeeId, now, null);
		em.persist(use);

		// when
		LedgerEntry cancel = account.cancelUse(use, Optional.empty(), "주문 착오", employeeId, now);
		em.persist(cancel);
		em.flush();
		em.clear();

		// then
		LedgerEntry reloaded = em.find(LedgerEntry.class, cancel.getId());
		assertThat(reloaded.getType()).isEqualTo(LedgerEntryType.USE_CANCEL);
		assertThat(reloaded.getReversesId()).isEqualTo(use.getId());
		assertThat(reloaded.getSignedAmount()).isEqualByComparingTo("3000");
		assertThat(reloaded.getBalanceAfter()).isEqualByComparingTo("10000");
		assertThat(em.find(PrepaidAccount.class, account.getId()).getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@Tag("REQ-34")
	@DisplayName("서비스가 기존 취소를 놓쳐도 같은 사용 건의 두 번째 취소는 DB 가 ux_ledger_entries_reverses 로 막는다")
	void second_use_cancel_is_blocked_by_db_unique_index() {
		// given: 서비스가 기존 취소 조회를 빠뜨려 도메인에 empty 를 두 번 넘긴 상황
		Instant now = Instant.parse("2026-09-30T01:00:00Z");
		PrepaidAccount account = PrepaidAccount.open(customerId, now);
		em.persist(account);
		em.persist(account.charge(BigDecimal.valueOf(10_000), POLICY, employeeId, now, null));
		LedgerEntry use = account.use(BigDecimal.valueOf(3_000), employeeId, now, null);
		em.persist(use);
		em.persist(account.cancelUse(use, Optional.empty(), "주문 착오", employeeId, now));
		em.flush();

		// when
		LedgerEntry duplicate = account.cancelUse(use, Optional.empty(), "중복 요청", employeeId, now);

		// then
		assertThatThrownBy(() -> {
			em.persist(duplicate);
			em.flush();
		}).rootCause()
				.isInstanceOfSatisfying(PSQLException.class, e -> assertThat(
						e.getServerErrorMessage().getConstraint()).isEqualTo("ux_ledger_entries_reverses"));
	}

	@Test
	@DisplayName("계좌 잔액이 바뀌면 version 이 올라간다")
	void version_increments_when_balance_changes() {
		// given
		Instant now = Instant.parse("2026-09-30T01:00:00Z");
		PrepaidAccount account = PrepaidAccount.open(customerId, now);
		em.persist(account);
		em.flush();
		Long initialVersion = account.getVersion();

		// when
		em.persist(account.charge(BigDecimal.valueOf(1_000), POLICY, employeeId, now, null));
		em.flush();

		// then
		assertThat(initialVersion).isNotNull();
		assertThat(account.getVersion()).isEqualTo(initialVersion + 1);
	}

}
