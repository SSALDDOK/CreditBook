package com.creditbook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.creditbook.support.RequiresDocker;

@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CreditbookApplicationTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("애플리케이션이 뜨고 DB에 연결된다")
	void context_loads_and_connects_to_database() {
		// given: Testcontainers PostgreSQL 로 컨텍스트가 떠 있다

		// when
		Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);

		// then
		assertThat(result).isEqualTo(1);
	}

}
