package com.creditbook.store.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.creditbook.store.application.StoreSetupResult;
import com.creditbook.store.application.StoreSetupService;
import com.creditbook.store.controller.dto.SetupRequest;
import com.creditbook.store.controller.dto.SetupResponse;
import com.creditbook.store.controller.dto.SetupStatusResponse;

import jakarta.validation.Valid;

/**
 * 매장 개설 API (REQ-29). 로그인 없이 부른다 — 직원 계정이 하나도 없을 때만 열리고, 하나라도 있으면 403 SETUP_ALREADY_COMPLETED.
 * 개설 뒤 자동 로그인(쿠키 발급)은 하지 않는다. 조회할 단건 경로가 없어 Location 헤더는 두지 않는다.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

	private final StoreSetupService storeSetupService;

	public SetupController(StoreSetupService storeSetupService) {
		this.storeSetupService = storeSetupService;
	}

	/** 매장 개설 가능 여부. 화면이 첫 화면을 개설 화면으로 보낼지 정할 때 쓴다. */
	@GetMapping("/status")
	public SetupStatusResponse status() {
		return new SetupStatusResponse(storeSetupService.isSetupAvailable());
	}

	/** 매장과 첫 ADMIN 계정을 만든다. */
	@PostMapping
	public ResponseEntity<SetupResponse> setUp(@Valid @RequestBody SetupRequest request) {
		StoreSetupResult result = storeSetupService.setUp(request.storeName(), request.ownerName(), request.loginId(),
				request.password());
		return ResponseEntity.status(HttpStatus.CREATED).body(SetupResponse.from(result));
	}

}
