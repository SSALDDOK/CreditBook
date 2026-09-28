package com.creditbook.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

class DockerAvailableCondition implements ExecutionCondition {

	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		if (DockerClientFactory.instance().isDockerAvailable()) {
			return ConditionEvaluationResult.enabled("Docker 사용 가능");
		}
		if (System.getenv("CI") != null) {
			throw new IllegalStateException("CI 에서 Docker 를 찾을 수 없다 — 통합 테스트를 건너뛰지 않고 실패 처리한다");
		}
		return ConditionEvaluationResult.disabled("로컬에 Docker 가 없어 통합 테스트를 건너뛴다");
	}

}
