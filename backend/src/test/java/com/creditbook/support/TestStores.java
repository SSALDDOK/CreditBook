package com.creditbook.support;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트용 매장 행 만들기 (REQ-36). 직원·고객은 매장이 있어야 저장된다(외래 키). JdbcTemplate 으로 넣으므로
 * 테스트 트랜잭션(@Transactional) 안이면 함께 롤백되고, 밖이면 커밋된다.
 * <ul>
 * <li>{@link #create} — 테스트마다·사장마다 새 매장. 이름은 중복을 허용하므로 같은 이름이어도 된다</li>
 * <li>{@link #ensure} — 정해진 id 의 매장이 없으면 만든다. {@link WithMockEmployee} 의 기본 매장처럼 id 를 미리 정해 둔 경우</li>
 * </ul>
 */
public final class TestStores {

	/** {@link WithMockEmployee#storeId()} 의 기본값. 어노테이션 값은 상수 문자열이어야 해서 문자열로 둔다. */
	public static final String DEFAULT_STORE_ID_VALUE = "00000000-0000-0000-0000-000000005001";

	/** {@link WithMockEmployee} 로 로그인한 직원의 기본 매장. */
	public static final UUID DEFAULT_STORE_ID = UUID.fromString(DEFAULT_STORE_ID_VALUE);

	private static final String DEFAULT_NAME = "테스트매장";

	private TestStores() {
	}

	/** 새 매장 행을 넣고 그 id 를 돌려준다. */
	public static UUID create(JdbcTemplate jdbcTemplate) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO stores (id, name) VALUES (?, ?)", id, DEFAULT_NAME);
		return id;
	}

	/** 그 id 의 매장 행이 없으면 넣는다. 있으면 그대로 둔다. id 를 그대로 돌려준다. */
	public static UUID ensure(JdbcTemplate jdbcTemplate, UUID id) {
		jdbcTemplate.update("INSERT INTO stores (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", id, DEFAULT_NAME);
		return id;
	}

}
