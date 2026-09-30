package com.creditbook.customer.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.creditbook.customer.domain.CustomerSearchKeyword;
import com.creditbook.customer.domain.CustomerSummary;

/**
 * 검색어 → 쿼리 선택과 LIKE 패턴 생성. 쿼리 자체(조인·정렬·비활성 제외)는 DB 가 있어야 검증되므로 S3 통합 테스트에서 본다.
 */
@ExtendWith(MockitoExtension.class)
@Tag("REQ-2")
class CustomerRepositoryImplTest {

	@Mock
	CustomerJpaRepository jpaRepository;

	@Mock
	Page<CustomerSummary> page;

	CustomerRepositoryImpl repository;

	@BeforeEach
	void setUp() {
		repository = new CustomerRepositoryImpl(jpaRepository);
	}

	@Test
	@DisplayName("검색어가 없으면 조건 없는 활성 고객 목록 쿼리를 쓴다")
	void no_keyword_uses_unfiltered_query() {
		// given
		given(jpaRepository.findActiveSummaries(any())).willReturn(page);

		// when
		Page<CustomerSummary> result = repository.searchActive(CustomerSearchKeyword.parse(null), PageRequest.of(0, 20));

		// then
		assertThat(result).isSameAs(page);
		verify(jpaRepository, never()).findActiveSummariesByNameLike(anyString(), any());
		verify(jpaRepository, never()).findActiveSummariesByPhoneLike(anyString(), any());
	}

	@ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
	@CsvSource({
			"김,     %김%",
			"Tom,    %tom%",
			"50%,    %50!%%",
			"a_b,    %a!_b%",
			"a!b,    %a!!b%" })
	@DisplayName("이름 검색은 부분 일치 패턴으로 바꾸고 LIKE 특수문자는 글자 그대로 찾는다")
	void name_keyword_uses_escaped_contains_pattern(String query, String expectedPattern) {
		// given
		given(jpaRepository.findActiveSummariesByNameLike(anyString(), any())).willReturn(page);

		// when
		repository.searchActive(CustomerSearchKeyword.parse(query), PageRequest.of(0, 20));

		// then
		verify(jpaRepository).findActiveSummariesByNameLike(eq(expectedPattern), any());
	}

	@Test
	@DisplayName("연락처 검색은 끝자리 일치 패턴으로 바꾼다")
	void phone_keyword_uses_suffix_pattern() {
		// given
		given(jpaRepository.findActiveSummariesByPhoneLike(anyString(), any())).willReturn(page);

		// when
		repository.searchActive(CustomerSearchKeyword.parse("56-78"), PageRequest.of(0, 20));

		// then
		verify(jpaRepository).findActiveSummariesByPhoneLike(eq("%5678"), any());
	}

	@Test
	@DisplayName("호출자가 넘긴 정렬은 버리고 페이지 번호·크기만 쿼리에 넘긴다")
	void caller_sort_is_dropped() {
		// given
		given(jpaRepository.findActiveSummaries(any())).willReturn(page);
		Pageable sorted = PageRequest.of(2, 50, Sort.by("name"));

		// when
		repository.searchActive(CustomerSearchKeyword.parse(""), sorted);

		// then
		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(jpaRepository).findActiveSummaries(captor.capture());
		assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
		assertThat(captor.getValue().getPageSize()).isEqualTo(50);
		assertThat(captor.getValue().getSort().isUnsorted()).isTrue();
	}

}
