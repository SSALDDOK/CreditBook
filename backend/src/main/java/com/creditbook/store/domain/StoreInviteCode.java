package com.creditbook.store.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 매장 개설 초대 코드 (store_invite_codes, REQ-36). 운영자가 무작위 코드를 만들어 전달하고, DB 에는 그 SHA-256 hex 만 둔다 —
 * 코드 원문은 저장하지 않는다. 코드는 지우지 않고 사용 중지만 한다.
 * <p>
 * 사용 횟수를 올리는 일은 조건부 UPDATE 한 문장으로 한다(V5 주석) — 이 엔티티로 읽고 고쳐 쓰지 않는다. 그래서 상태를 바꾸는
 * 메서드를 두지 않는다. 발급·사용 API 는 아직 없다.
 */
@Entity
@Table(name = "store_invite_codes")
public class StoreInviteCode {

	/** store_invite_codes.max_uses 의 DB 기본값. */
	public static final int DEFAULT_MAX_USES = 10;

	/** store_invite_codes.label VARCHAR(100) */
	public static final int LABEL_MAX_LENGTH = 100;

	/** ck_store_invite_codes_code_hash 와 같은 규칙 — 소문자 hex 64자. */
	private static final Pattern CODE_HASH = Pattern.compile("^[0-9a-f]{64}$");

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "code_hash", nullable = false, updatable = false, length = 64)
	private String codeHash;

	@Column(name = "label", nullable = false, length = LABEL_MAX_LENGTH)
	private String label;

	@Column(name = "max_uses", nullable = false)
	private int maxUses;

	@Column(name = "used_count", nullable = false)
	private int usedCount;

	@Column(name = "active", nullable = false)
	private boolean active;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "disabled_at")
	private Instant disabledAt;

	protected StoreInviteCode() {
		// JPA 전용
	}

	private StoreInviteCode(String codeHash, String label, int maxUses, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.codeHash = requireCodeHash(codeHash);
		this.label = requireLabel(label);
		if (maxUses < 1) {
			throw new IllegalArgumentException("maxUses must be at least 1");
		}
		this.maxUses = maxUses;
		this.usedCount = 0;
		this.active = true;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
	}

	/**
	 * 새 초대 코드를 만든다 (사용 0회, 사용 중).
	 *
	 * @param codeHash 코드 원문의 SHA-256 소문자 hex 64자
	 * @param label 어느 지원 건에 준 코드인지 알아보는 메모 — 이름·연락처 같은 개인정보는 넣지 않는다
	 * @throws IllegalArgumentException 해시 형식이 틀리거나, 메모가 비었거나 100자를 넘거나, 한도가 1 미만일 때
	 */
	public static StoreInviteCode issue(String codeHash, String label, int maxUses, Instant createdAt) {
		return new StoreInviteCode(codeHash, label, maxUses, createdAt);
	}

	private static String requireCodeHash(String codeHash) {
		if (codeHash == null || !CODE_HASH.matcher(codeHash).matches()) {
			throw new IllegalArgumentException("codeHash must be 64 lowercase hex characters");
		}
		return codeHash;
	}

	private static String requireLabel(String label) {
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("label must not be blank");
		}
		String stripped = label.strip();
		if (stripped.codePointCount(0, stripped.length()) > LABEL_MAX_LENGTH) {
			throw new IllegalArgumentException("label must be at most " + LABEL_MAX_LENGTH + " characters");
		}
		return stripped;
	}

	/** 이 코드로 매장을 하나 더 열 수 있는가 — 사용 중이고 한도가 남았을 때. */
	public boolean canBeUsed() {
		return active && usedCount < maxUses;
	}

	public UUID getId() {
		return id;
	}

	public String getCodeHash() {
		return codeHash;
	}

	public String getLabel() {
		return label;
	}

	public int getMaxUses() {
		return maxUses;
	}

	public int getUsedCount() {
		return usedCount;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getDisabledAt() {
		return disabledAt;
	}

}
