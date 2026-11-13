package com.creditbook.store.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 매장 (stores, REQ-36). 직원·고객·계좌·거래는 모두 한 매장에 속한다 — 매장 사이 연결은 DB 의 복합 외래 키가 막는다.
 * 매장 이름은 중복을 허용한다(매장은 id 로 구분한다). 매장은 매장 개설(REQ-29)로 첫 사장(ADMIN) 계정과 함께 만든다.
 */
@Entity
@Table(name = "stores")
public class Store {

	/** stores.name VARCHAR(30). 앞뒤 공백을 뺀 글자 수. */
	public static final int NAME_MAX_LENGTH = 30;

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
	private String name;

	/** 이 매장을 열 때 쓴 초대 코드 (store_invite_codes.id). 초대 코드 없이 열린 매장은 null — 운영자만 본다. */
	@Column(name = "invite_code_id", updatable = false)
	private UUID inviteCodeId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Store() {
		// JPA 전용
	}

	private Store(String name, UUID inviteCodeId, Instant openedAt) {
		this.id = UUID.randomUUID();
		this.name = requireValidName(name);
		this.inviteCodeId = inviteCodeId;
		this.createdAt = Objects.requireNonNull(openedAt, "openedAt");
		this.updatedAt = openedAt;
	}

	/**
	 * 초대 코드 없이 매장을 연다 (지금의 매장 개설 경로). 매장명은 앞뒤 공백을 빼고 저장한다.
	 * 초대 코드로 여는 경로는 초대 코드 API 와 함께 추가한다.
	 *
	 * @throws InvalidStoreNameException 매장명이 비었거나 공백뿐이거나, 앞뒤 공백을 빼고 {@value #NAME_MAX_LENGTH}자를 넘을 때
	 */
	public static Store open(String name, Instant openedAt) {
		return new Store(name, null, openedAt);
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

	public UUID getInviteCodeId() {
		return inviteCodeId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
