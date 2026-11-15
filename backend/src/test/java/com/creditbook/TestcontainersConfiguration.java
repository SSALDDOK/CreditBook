package com.creditbook;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.creditbook.support.NeighborStore;

/**
 * 통합 테스트용 PostgreSQL. H2 는 GENERATED 컬럼·부분 인덱스 동작이 달라 쓰지 않는다.
 * 이미지 메이저 버전은 Neon 프로젝트의 PostgreSQL 버전과 맞춘다.
 * <p>
 * 이 설정을 쓰는 테스트 DB 에는 늘 데이터가 찬 이웃 매장이 함께 있다({@link NeighborStore}, REQ-36) — 모든 통합 테스트가
 * 매장 2개 조건에서 돌고, 클래스가 끝날 때 이웃 매장이 그대로인지 확인된다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(NeighborStore.class)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
	}

}
