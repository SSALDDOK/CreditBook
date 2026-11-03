package com.creditbook.auth.domain;

import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * 직원 추가·비밀번호 초기화 때 사장에게 1회만 보여 줄 초기 비밀번호를 만든다 (REQ-30).
 * <p>
 * 형식: 헷갈리는 글자(0 O 1 l I)를 뺀 {@value #ALPHABET} 에서 무작위로 고른 12자를 4자씩 하이픈으로 이은 14자
 * (예: {@code Rv7m-Kq2x-9Tn4}). 하이픈까지 포함한 전체가 비밀번호다 — 로그인 때 하이픈을 빼지 않는다.
 * 14자·ASCII 라 {@link EmployeeCredentialRules} 의 길이(8–72자)·바이트(72) 규칙을 항상 지킨다.
 * <p>
 * 난수는 주입받는다. 운영은 SecureRandom, 테스트는 시드를 고정한 생성기를 쓴다.
 */
public class InitialPasswordGenerator {

	/** 대문자(I·O 제외) 24 + 소문자(l 제외) 25 + 숫자(0·1 제외) 8 = 57자. */
	public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

	public static final int GROUP_COUNT = 3;

	public static final int GROUP_LENGTH = 4;

	private final RandomGenerator random;

	public InitialPasswordGenerator(RandomGenerator random) {
		this.random = Objects.requireNonNull(random, "random");
	}

	public String generate() {
		StringBuilder password = new StringBuilder(GROUP_COUNT * (GROUP_LENGTH + 1) - 1);
		for (int group = 0; group < GROUP_COUNT; group++) {
			if (group > 0) {
				password.append('-');
			}
			for (int i = 0; i < GROUP_LENGTH; i++) {
				password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
			}
		}
		return password.toString();
	}

}
