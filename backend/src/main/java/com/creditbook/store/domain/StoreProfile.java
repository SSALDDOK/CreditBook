package com.creditbook.store.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 매장 정보 (store_profile). 단일 매장용이라 행은 하나뿐이다 — 두 번째 행은 DB 의 ux_store_profile_singleton 이 막는다.
 * 매장은 매장 개설(REQ-29)로 처음 ADMIN 계정과 함께 한 번 만든다.
 */
@Entity
@Table(name = "store_profile")
public class StoreProfile {

	/** store_profile.name VARCHAR(30). 앞뒤 공백을 뺀 글자 수. */
	public static final int NAME_MAX_LENGTH = 30;

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
	private String name;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected StoreProfile() {
		// JPA 전용
	}

	private StoreProfile(String name, Instant openedAt) {
		this.id = UUID.randomUUID();
		this.name = requireValidName(name);
		this.createdAt = Objects.requireNonNull(openedAt, "openedAt");
		this.updatedAt = openedAt;
	}

	/**
	 * 매장을 연다. 매장명은 앞뒤 공백을 빼고 저장한다.
	 *
	 * @throws InvalidStoreNameException 매장명이 비었거나 공백뿐이거나, 앞뒤 공백을 빼고 {@value #NAME_MAX_LENGTH}자를 넘을 때
	 */
	public static StoreProfile open(String name, Instant openedAt) {
		return new StoreProfile(name, openedAt);
	}

	/** 글자 수는 코드 포인트로 센다 (PostgreSQL VARCHAR 의 글자 수와 같다). */
	private static String requireValidName(String name) {
		if (name == null || name.isBlank()) {
			throw new InvalidStoreNameException("매장명을 입력해 주세요.");
		}
		String stripped = name.strip();
		if (stripped.codePointCount(0, stripped.length()) > NAME_MAX_LENGTH) {
			throw new InvalidStoreNameException("매장명은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
		}
		return stripped;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
