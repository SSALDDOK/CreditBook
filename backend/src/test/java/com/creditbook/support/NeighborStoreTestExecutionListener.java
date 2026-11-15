package com.creditbook.support;

import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * 모든 통합 테스트에 이웃 매장({@link NeighborStore}) 확인을 건다 (REQ-36, TC-27·TC-52).
 * 클래스마다 붙이지 않고 {@code META-INF/spring.factories} 로 등록한다 — 새 통합 테스트도 따로 할 일이 없다.
 * 컨텍스트에 이웃 매장이 있을 때만 본다(단위 테스트·웹 계층 테스트는 그냥 지나간다).
 * <ul>
 * <li>메서드가 끝날 때 — 이웃 매장에 쓰려는 시도가 있었으면 그 메서드가 실패한다.</li>
 * <li>클래스가 끝날 때 — 시도 횟수와 함께, 이웃 매장이 처음 그대로인지 비교한다.</li>
 * </ul>
 * 순서 4500: 끝날 때는 큰 번호부터 돌므로 테스트 트랜잭션 롤백(4000)보다 먼저 본다. {@code @Sql} 처리(5000)와 겹치지 않게
 * 그 사이에 둔다.
 */
public class NeighborStoreTestExecutionListener implements TestExecutionListener, Ordered {

	@Override
	public int getOrder() {
		return 4500;
	}

	@Override
	public void afterTestMethod(TestContext testContext) {
		NeighborStore neighbor = neighborOf(testContext);
		if (neighbor != null) {
			neighbor.assertNoWriteAttempts(
					testContext.getTestClass().getSimpleName() + "#" + testContext.getTestMethod().getName());
		}
	}

	@Override
	public void afterTestClass(TestContext testContext) {
		NeighborStore neighbor = neighborOf(testContext);
		if (neighbor != null) {
			String testClass = testContext.getTestClass().getSimpleName();
			neighbor.assertNoWriteAttempts(testClass);
			neighbor.assertUnchanged(testClass);
		}
	}

	private static NeighborStore neighborOf(TestContext testContext) {
		if (!testContext.hasApplicationContext()) {
			return null; // 컨텍스트가 뜨지 못했으면 그 실패가 이미 보인다
		}
		return testContext.getApplicationContext().getBeanProvider(NeighborStore.class).getIfAvailable();
	}

}
