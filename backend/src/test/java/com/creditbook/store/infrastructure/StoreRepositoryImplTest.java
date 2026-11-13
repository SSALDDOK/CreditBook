package com.creditbook.store.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.creditbook.store.domain.Store;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

/**
 * 매장 행 저장 (REQ-29·36). 실제 INSERT 와 제약은 통합 테스트가 본다 — 여기서는 persist 뒤 바로 flush 하는지,
 * 저장 오류를 바꾸지 않고 그대로 던지는지만 본다.
 */
@Tag("REQ-36")
class StoreRepositoryImplTest {

	private final EntityManager em = mock(EntityManager.class);
	private final StoreRepositoryImpl repository = new StoreRepositoryImpl();
	private final Store store = Store.open("카페 새벽", UUID.randomUUID(), Instant.parse("2026-10-30T01:00:00Z"));

	StoreRepositoryImplTest() {
		ReflectionTestUtils.setField(repository, "em", em);
	}

	@Test
	@DisplayName("매장을 추가하면 persist 뒤 바로 DB 에 보낸다")
	void add_persists_then_flushes() {
		// when
		repository.add(store);

		// then
		InOrder order = inOrder(em);
		order.verify(em).persist(store);
		order.verify(em).flush();
	}

	@Test
	@DisplayName("저장 오류는 바꾸지 않고 그대로 던진다")
	void persistence_error_is_rethrown() {
		// given
		PersistenceException cause = new PersistenceException("connection lost");
		willThrow(cause).given(em).flush();

		// when / then
		assertThatThrownBy(() -> repository.add(store)).isSameAs(cause);
	}

}
