package com.creditbook.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 초기 비밀번호 형식 (REQ-30). 난수는 시드를 고정해 결과가 매번 같다.
 */
@Tag("REQ-30")
class InitialPasswordGeneratorTest {

	private static final String SET = "[ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789]";
	private static final Pattern FORMAT = Pattern.compile("^" + SET + "{4}-" + SET + "{4}-" + SET + "{4}$");
	private static final int REPEAT = 2_000;

	@ParameterizedTest(name = "[{index}] seed={0}")
	@ValueSource(longs = { 1L, 42L, 20261103L })
	@DisplayName("초기 비밀번호는 헷갈리는 글자를 뺀 12자를 4자씩 하이픈으로 이은 14자이고 계정 비밀번호 규칙을 지킨다")
	void generated_password_has_fixed_format(long seed) {
		// given
		InitialPasswordGenerator generator = new InitialPasswordGenerator(new Random(seed));

		for (int i = 0; i < REPEAT; i++) {
			// when
			String password = generator.generate();

			// then
			assertThat(password).hasSize(14).matches(FORMAT);
			assertThat(password).doesNotContain("0", "O", "1", "l", "I");
			assertThat(password.length()).isBetween(EmployeeCredentialRules.PASSWORD_MIN_LENGTH,
					EmployeeCredentialRules.PASSWORD_MAX_LENGTH);
			assertThat(EmployeeCredentialRules.isWithinPasswordBytes(password)).isTrue();
		}
	}

	@Test
	@DisplayName("문자 집합은 대문자 24·소문자 25·숫자 8 = 57자이고 헷갈리는 글자가 없다")
	void alphabet_excludes_confusing_characters() {
		assertThat(InitialPasswordGenerator.ALPHABET).hasSize(57).doesNotContain("0", "O", "1", "l", "I");
		assertThat(InitialPasswordGenerator.ALPHABET.chars().distinct().count()).isEqualTo(57);
	}

	@Test
	@DisplayName("같은 시드면 같은 순서로, 서로 다른 호출은 서로 다른 비밀번호를 만든다")
	void generation_is_deterministic_per_seed_and_varies_per_call() {
		// given
		InitialPasswordGenerator first = new InitialPasswordGenerator(new Random(7L));
		InitialPasswordGenerator second = new InitialPasswordGenerator(new Random(7L));
		Set<String> seen = new HashSet<>();

		// when
		for (int i = 0; i < 100; i++) {
			String a = first.generate();
			String b = second.generate();

			// then
			assertThat(a).isEqualTo(b);
			seen.add(a);
		}
		assertThat(seen).hasSize(100);
	}

	@Test
	@DisplayName("문자 집합의 모든 글자가 나올 수 있다 (집합 끝 글자 포함)")
	void every_character_can_appear() {
		// given
		InitialPasswordGenerator generator = new InitialPasswordGenerator(new Random(3L));
		Set<Character> used = new HashSet<>();

		// when
		for (int i = 0; i < REPEAT; i++) {
			for (char c : generator.generate().replace("-", "").toCharArray()) {
				used.add(c);
			}
		}

		// then
		assertThat(used).hasSize(InitialPasswordGenerator.ALPHABET.length());
	}

	@Test
	@DisplayName("난수 생성기 없이는 만들 수 없다")
	void null_random_is_rejected() {
		assertThatThrownBy(() -> new InitialPasswordGenerator(null)).isInstanceOf(NullPointerException.class);
	}

}
