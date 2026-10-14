package com.creditbook.prepaid.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.persistence.PersistenceException;

/**
 * 저장 실패를 어느 제약 위반으로 볼지 (REQ-10 요청 키, 반제 1건 규칙). 실제 PostgreSQL 위반은 통합 테스트가 확인한다.
 */
class LedgerEntryRepositoryImplTest {

	private static PersistenceException wrapped(String constraintName) {
		ConstraintViolationException violation = new ConstraintViolationException("duplicate key",
				new SQLException("duplicate key", "23505"), constraintName);
		return new PersistenceException("could not execute statement", violation);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { LedgerEntryRepositoryImpl.IDEMPOTENCY_KEY_INDEX, LedgerEntryRepositoryImpl.REVERSES_INDEX,
			"ux_ledger_entries_seq" })
	@DisplayName("원인 사슬 안의 제약 위반에서 제약 이름을 꺼낸다")
	void finds_constraint_name_in_cause_chain(String constraintName) {
		assertThat(LedgerEntryRepositoryImpl.violatedConstraint(wrapped(constraintName))).isEqualTo(constraintName);
	}

	@Test
	@DisplayName("원인 사슬에 제약 위반이 없으면 제약 이름은 없다")
	void returns_null_without_constraint_violation() {
		assertThat(LedgerEntryRepositoryImpl.violatedConstraint(
				new PersistenceException("connection lost", new SQLException("timeout")))).isNull();
	}

}
