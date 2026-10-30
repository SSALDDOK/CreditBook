package com.creditbook.store.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.time.Instant;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.creditbook.store.domain.SetupAlreadyCompletedException;
import com.creditbook.store.domain.StoreProfile;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

/**
 * 매장 행 저장 시 제약 위반 변환 (REQ-29). 실제 DB 의 위반 메시지에서 제약 이름을 읽는 동작은 통합 테스트가 본다 —
 * 여기서는 ux_store_profile_singleton 일 때만 "이미 개설됨" 으로 바꾸고 나머지는 그대로 던지는지만 본다.
 */
@Tag("REQ-29")
class StoreProfileRepositoryImplTest {

	private final EntityManager em = mock(EntityManager.class);
	private final StoreProfileRepositoryImpl repository = new StoreProfileRepositoryImpl();
	private final StoreProfile store = StoreProfile.open("카페 새벽", Instant.parse("2026-10-30T01:00:00Z"));

	StoreProfileRepositoryImplTest() {
		ReflectionTestUtils.setField(repository, "em", em);
	}

	private static ConstraintViolationException violation(String constraintName) {
		return new ConstraintViolationException("could not execute statement", new SQLException("23505"), constraintName);
	}

	@Test
	@DisplayName("매장 행이 이미 있어 ux_store_profile_singleton 에 걸리면 이미 개설된 매장으로 거절한다")
	void singleton_violation_becomes_setup_already_completed() {
		// given
		ConstraintViolationException cause = violation("ux_store_profile_singleton");
		willThrow(cause).given(em).flush();

		// when / then
		assertThatThrownBy(() -> repository.add(store))
				.isInstanceOf(SetupAlreadyCompletedException.class)
				.hasCause(cause);
		verify(em).persist(store);
	}

	@Test
	@DisplayName("다른 제약 위반은 이미 개설된 매장으로 바꾸지 않고 그대로 던진다")
	void other_violation_is_rethrown() {
		// given
		ConstraintViolationException cause = violation("ck_store_profile_name");
		willThrow(cause).given(em).flush();

		// when / then
		assertThatThrownBy(() -> repository.add(store)).isSameAs(cause);
	}

	@Test
	@DisplayName("제약 위반이 아닌 저장 오류도 그대로 던진다")
	void non_constraint_error_is_rethrown() {
		// given
		PersistenceException cause = new PersistenceException("connection lost");
		willThrow(cause).given(em).flush();

		// when / then
		assertThatThrownBy(() -> repository.add(store)).isSameAs(cause);
	}

}
