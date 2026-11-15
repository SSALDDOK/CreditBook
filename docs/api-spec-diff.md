# API 명세와 구현 차이 목록

기준일 2026-11-15 (S7 마감). 구현은 `main`의 컨트롤러·`ErrorCode`·`SecurityConfig`를 읽어 뽑았다.

## 이 문서를 읽는 법

이 프로젝트의 API 명세는 한 문서에 모여 있지 않다. API마다 설계를 실제 JSON 예시로 승인받은 뒤 구현했고, 그 기록이 여러 곳에 나뉘어 있다.

| 무엇 | 어디에 |
|---|---|
| 업무 규칙(경계·예외 조건) | Notion 요구사항 DB의 REQ 행 |
| 경로·요청·응답 (S5 이후 API) | Jira 이슈 설명의 '확정 API' 절, 화면 목록 DB의 '연결 API' |
| 오류 응답 형식과 오류 코드 | Notion 개발 컨벤션 §2.2 |
| **확정본** | 코드 - 요청·응답 DTO, `ErrorCode`, `GlobalExceptionHandler`. 응답 모양은 통합 테스트가 검증한다 |

그래서 아래 1절은 코드에서 뽑은 API 목록이고, 2절은 문서에 적힌 내용이 그 목록과 다른 곳이다. **2절에서 구현이 틀린 곳은 없었다** - 모두 구현 뒤 문서가 따라가지 못한 경우다.

## 1. API 목록 (구현 기준)

공통

- 모든 요청·응답은 JSON, 오류는 `{code, message, fieldErrors, details?}` 한 가지 모양
- 로그인은 세션 쿠키(JWT). 아래 '공개' 표시가 없는 API는 로그인이 필요하다 - 없으면 401 `UNAUTHENTICATED`
- 비밀번호 변경이 필요한 계정은 세션 조회·비밀번호 변경 외 모든 API에서 403 `PASSWORD_CHANGE_REQUIRED`
- 요청 본문에 정의되지 않은 항목이 있으면 400 `INVALID_INPUT`(fieldErrors에 그 항목 이름)
- 다른 매장의 고객·거래·직원은 없는 것과 같이 404
- 거래를 고치거나 지우는 경로는 없다 - PUT·PATCH·DELETE를 보내면 405 또는 404

### 인증

| 메서드 · 경로 | 권한 | 성공 | 주요 오류 |
|---|---|---|---|
| `POST /api/auth/login` | 공개 | 200 세션 정보 + 쿠키 | 401 `INVALID_CREDENTIALS` · 423 `ACCOUNT_LOCKED`(details.retryAfterSeconds, Retry-After) · 429 `LOGIN_RATE_LIMITED`(Retry-After) |
| `POST /api/auth/logout` | 공개 | 204, 토큰 폐기 | - |
| `GET /api/auth/session` | 로그인 | 200 세션 정보(이름·역할·매장명·만료 시각·비밀번호 변경 필요 여부) | 401 |
| `PUT /api/auth/password` | 로그인 | 200 세션 정보 | 400 `INVALID_CURRENT_PASSWORD` · 400 `PASSWORD_UNCHANGED` · 423 `ACCOUNT_LOCKED` |

### 매장

| 메서드 · 경로 | 권한 | 성공 | 주요 오류 |
|---|---|---|---|
| `POST /api/setup` | 공개 | 201 `{storeName, loginId, name, role}` (자동 로그인 없음) | 400 `INVALID_INVITE_CODE` · 409 `DUPLICATE_LOGIN_ID` · 429 `SIGNUP_RATE_LIMITED`(Retry-After) |
| `GET /api/employees` | ADMIN | 200 `{items}` | 403 `FORBIDDEN` |
| `POST /api/employees` | ADMIN | 201, 초기 비밀번호 1회 | 409 `DUPLICATE_LOGIN_ID` |
| `POST /api/employees/{id}/reset-password` | ADMIN | 200, 새 초기 비밀번호 | 404 `EMPLOYEE_NOT_FOUND` · 409 `ADMIN_ACCOUNT_PROTECTED` |
| `PATCH /api/employees/{id}/deactivate` | ADMIN | 204 (이미 비활성도 204) | 404 `EMPLOYEE_NOT_FOUND` · 409 `ADMIN_ACCOUNT_PROTECTED` |

### 고객

| 메서드 · 경로 | 권한 | 성공 | 주요 오류 |
|---|---|---|---|
| `POST /api/customers` | 로그인 | 201 + Location | 409 `DUPLICATE_PHONE`(details.existingCustomers, `confirmDuplicatePhone: true`로 다시 보내면 저장) |
| `GET /api/customers` | 로그인 | 200 목록. `q`(20자 이하), `includeInactive`(기본 false), `page`(0부터), `size`(기본 20, 최대 100) | 400 `INVALID_INPUT` |
| `GET /api/customers/{id}` | 로그인 | 200 잔액·최근 거래 5건·`memo`·`version`, 연락처는 가린 값 | 404 `CUSTOMER_NOT_FOUND` |
| `PATCH /api/customers/{id}` | 로그인 | 200 상세와 같은 모양 | 409 `CONCURRENT_MODIFICATION`(version 불일치) · 409 `DUPLICATE_PHONE` · 404 |
| `PATCH /api/customers/{id}/deactivate` | ADMIN | 204 (이미 비활성도 204) | 409 `CUSTOMER_BALANCE_NOT_ZERO`(details.balance) · 403 `FORBIDDEN` |

### 장부 (충전·사용·취소·이력)

충전·사용·충전 취소·사용 취소는 헤더 `Idempotency-Key`(영문·숫자·하이픈 1–64자)가 필수다. 같은 키로 같은 요청이 다시 오면 처음 응답을 201 + `Idempotent-Replayed: true`로 다시 주고, 다른 요청이면 422 `IDEMPOTENCY_KEY_REUSED`, 헤더가 없거나 형식이 틀리면 400 `INVALID_IDEMPOTENCY_KEY`.

| 메서드 · 경로 | 권한 | 성공 | 주요 오류 |
|---|---|---|---|
| `POST /api/customers/{customerId}/charges` | 로그인 | 201 거래·거래 후 잔액 (비활성 고객은 자동 재활성화) | 400 `INVALID_AMOUNT` · 400 `CHARGE_LIMIT_EXCEEDED` · 409 `CONCURRENT_MODIFICATION` |
| `POST /api/customers/{customerId}/uses` | 로그인 | 201 거래·거래 후 잔액 | 400 `INSUFFICIENT_BALANCE`(details.balance·shortage) · 409 `CUSTOMER_INACTIVE` · 409 `CONCURRENT_MODIFICATION` |
| `POST /api/customers/{customerId}/charges/{chargeEntryId}/cancel` | 로그인 | 201 반제 거래 | 409 `CHARGE_CANCEL_NOT_SAME_DAY` · 409 `CHARGE_ALREADY_USED`(details.balance·chargeAmount) · 409 `ALREADY_CANCELLED` · 400 `INVALID_CANCEL_TARGET` · 404 `LEDGER_ENTRY_NOT_FOUND` |
| `POST /api/customers/{customerId}/uses/{useEntryId}/cancel` | 로그인 | 201 반제 거래 (비활성 고객은 자동 재활성화) | 409 `ALREADY_CANCELLED` · 409 `CONCURRENT_MODIFICATION` · 400 `INVALID_CANCEL_TARGET` · 404 `LEDGER_ENTRY_NOT_FOUND` |
| `GET /api/customers/{customerId}/transactions` | 로그인 | 200 최신순, 행마다 거래 후 잔액·취소 가능 여부. `page`, `size`(기본 20, 최대 100) | 404 `CUSTOMER_NOT_FOUND` |
| `GET /api/settings/ledger-input` | 로그인 | 200 `{chargeMaxAmount, chargeMemoPresets, useMemoPresets, memoMaxLength}` | - |

## 2. 문서와 다른 곳

| # | 문서 | 문서에 적힌 것 | 구현 | 처리 |
|---|---|---|---|---|
| 1 | 개발 컨벤션 §2.2 오류 코드 | 오류 코드 목록에 S7 코드가 없다 | `INVALID_INVITE_CODE`(400)·`SIGNUP_RATE_LIMITED`(429)·`INVALID_REQUEST_PATH`(400, `//`·`..`가 든 주소)가 있다 | 문서에 추가 |
| 2 | 개발 컨벤션 §2.2 오류 코드 | `CONCURRENT_MODIFICATION`이 목록에 없다('동시 수정 409'라는 한 줄만) | 409 `CONCURRENT_MODIFICATION` - 같은 계좌 동시 충전·사용, 고객 정보 수정·비활성화의 버전 불일치 | 문서에 추가 |
| 3 | 개발 컨벤션 §2.2 오류 코드 | `DUPLICATE_PHONE`이 고객 등록 기준으로만 적혀 있다 | 고객 정보 수정에도 같은 코드(같은 매장, 수정 중인 고객 자신 제외, 연락처가 실제로 바뀔 때만) | 문서에 추가 |
| 4 | 개발 컨벤션 §2.2 오류 코드 | 공통 오류 코드가 목록에 없다 | `UNAUTHENTICATED`(401)·`INVALID_CREDENTIALS`(401)·`FORBIDDEN`(403)·`CUSTOMER_NOT_FOUND`·`LEDGER_ENTRY_NOT_FOUND`·`EMPLOYEE_NOT_FOUND`·`NOT_FOUND`(404)·`METHOD_NOT_ALLOWED`(405)·`UNSUPPORTED_MEDIA_TYPE`(415)·`MALFORMED_REQUEST`·`INVALID_CUSTOMER_NAME`·`INVALID_PHONE_NUMBER`·`INVALID_AMOUNT`·`CHARGE_LIMIT_EXCEEDED`·`INVALID_CANCEL_TARGET`(400)·`INTERNAL_ERROR`(500) | 표 하나로 추가(코드·HTTP·문구). 확정본은 `ErrorCode` |
| 5 | 요구사항 REQ-10 '요청 키 동작 규칙' | 규칙 표의 대상이 '충전·사용 API' | 충전·사용·**충전 취소·사용 취소** 4개 API 모두 같은 규칙(헤더 필수) | 대상에 두 취소 API 추가 |
| 6 | 개발 컨벤션 §2.3 프론트 환경변수 | `VITE_MOCK_SETUP_AVAILABLE`(목 모드의 가입 가능 상태) | 여러 매장 전환으로 '가입 가능 여부' 개념이 없어져 삭제됨. 대신 `VITE_API_BASE_URL`(API 주소 앞부분, 비우면 같은 주소)이 있다 | 행 교체 |
| 7 | 개발 컨벤션 §2 패키지 구조 | `customer`·`prepaid`·`auth`만 있다 | `store`(매장·초대 코드 가입) 패키지, `prepaid/controller`의 `CancelController`·`LedgerInputSettingsController`, `auth/domain`의 `EmployeeCredentialRules` | 구조도 갱신 |
| 8 | 리포 작업 지침 파일(REQ-29 줄, 패키지 구조, `store_profile` 행) | `GET /api/setup/status`, 403 `SETUP_ALREADY_COMPLETED`, 단일 행 `store_profile` | 여러 매장 전환(V5)으로 셋 다 없어졌다 - 가입은 초대 코드, 표는 `stores` | 같은 스프린트 문서 정리에서 고침 |

이전에 맞춘 것(참고): 고객 정보 수정 API의 확정 명세(경로·요청 항목·오류 표)는 Jira 설명·화면 목록 '연결 API'·REQ-3 본문에 옮기는 중이다. 가입 API의 옛 경로(`GET /api/setup/status`)는 요구사항·화면 문서에서 2026-11-13에 정리됐다.
