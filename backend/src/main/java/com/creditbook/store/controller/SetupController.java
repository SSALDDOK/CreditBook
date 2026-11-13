package com.creditbook.store.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.creditbook.store.application.StoreSetupResult;
import com.creditbook.store.application.StoreSetupService;
import com.creditbook.store.controller.dto.SetupRequest;
import com.creditbook.store.controller.dto.SetupResponse;

import jakarta.validation.Valid;

/**
 * 초대 코드 가입 API (REQ-29·36). 로그인 없이 부른다 — 초대 코드가 맞으면 새 매장과 그 사장 계정을 만든다.
 * 코드가 없거나 꺼졌거나 다 쓰였으면 400 INVALID_INVITE_CODE, 아이디가 이미 있으면 409 DUPLICATE_LOGIN_ID.
 * 같은 IP 의 요청 수는 보안 필터가 먼저 센다 (429 SIGNUP_RATE_LIMITED).
 * 가입 뒤 자동 로그인(쿠키 발급)은 하지 않는다. 조회할 단건 경로가 없어 Location 헤더는 두지 않는다.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

	private final StoreSetupService storeSetupService;

	public SetupController(StoreSetupService storeSetupService) {
		this.storeSetupService = storeSetupService;
	}

	/** 초대 코드로 새 매장과 그 사장 계정을 만든다. */
	@PostMapping
	public ResponseEntity<SetupResponse> setUp(@Valid @RequestBody SetupRequest request) {
		StoreSetupResult result = storeSetupService.setUp(request.inviteCode(), request.storeName(), request.ownerName(),
				request.loginId(), request.password());
		return ResponseEntity.status(HttpStatus.CREATED).body(SetupResponse.from(result));
	}

}
