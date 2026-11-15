package com.creditbook.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 테스트 클래스가 끝날 때마다 이웃 매장({@link NeighborStore})이 처음 그대로인지 본다 (REQ-36, TC-27·TC-52).
 * 클래스마다 붙이지 않고 JUnit 자동 등록으로 모든 테스트에 걸린다 — {@code junit-platform.properties} 와
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension}. 새 통합 테스트도 따로 할 일이 없다.
 * <p>
 * {@code @SpringBootTest} 이고 컨텍스트에 이웃 매장이 있을 때만 본다 — 단위 테스트·웹 계층 테스트는 그냥 지나간다.
 */
public class NeighborStoreUnchangedExtension implements AfterAllCallback {

	@Override
	public void afterAll(ExtensionContext context) {
		Class<?> testClass = context.getRequiredTestClass();
		if (!AnnotatedElementUtils.hasAnnotation(testClass, SpringBootTest.class)) {
			return;
		}
		ApplicationContext applicationContext = SpringExtension.getApplicationContext(context);
		NeighborStore neighbor = applicationContext.getBeanProvider(NeighborStore.class).getIfAvailable();
		if (neighbor != null) {
			neighbor.assertUnchanged(testClass.getSimpleName());
		}
	}

}
