/**
 * 요청 DTO 에 쓰는 공통 Bean Validation 제약 — 앞뒤 공백을 뺀 글자 수, UTF-8 바이트 길이, 비밀번호 확인 일치.
 * 위반은 다른 형식 오류와 같이 MethodArgumentNotValidException → 400 INVALID_INPUT(fieldErrors) 로 응답된다.
 */
package com.creditbook.global.validation;
