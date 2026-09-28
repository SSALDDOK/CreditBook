---
name: api-design
description: "REST API 설계 패턴 — 리소스 네이밍, 상태 코드, 페이지네이션, 필터링, 에러 응답, 버저닝, 레이트 리밋. CreditBook은 Spring Boot만 쓰므로 원본 ECC 스킬의 TypeScript/Django/Go 구현 예시는 Spring Boot 예시로 교체했다. REST 엔드포인트/리소스명/상태코드/페이지네이션/버저닝 설계나 리뷰 시 사용."
metadata:
  origin: ECC (implementation examples replaced with Spring Boot)
---

# API 설계 패턴

일관되고 쓰기 쉬운 REST API를 설계하기 위한 컨벤션과 모범 사례.

## 언제 쓰는가

- 새 API 엔드포인트 설계
- 기존 API 계약 리뷰
- 페이지네이션, 필터링, 정렬 추가
- API 오류 처리 구현
- API 버저닝 전략 수립
- 공개·파트너용 API 구축

## 리소스 설계

### URL 구조

```
# 리소스는 명사, 복수형, 소문자, kebab-case
GET    /api/v1/users
GET    /api/v1/users/:id
POST   /api/v1/users
PUT    /api/v1/users/:id
PATCH  /api/v1/users/:id
DELETE /api/v1/users/:id

# 관계는 하위 리소스로
GET    /api/v1/users/:id/orders
POST   /api/v1/users/:id/orders

# CRUD로 표현되지 않는 동작 (동사는 최소한으로)
POST   /api/v1/orders/:id/cancel
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh
```

### 네이밍 규칙

```
# 좋음
/api/v1/team-members          # 여러 단어 리소스는 kebab-case
/api/v1/orders?status=active  # 필터링은 쿼리 파라미터로
/api/v1/users/123/orders      # 소유 관계는 중첩 리소스로

# 나쁨
/api/v1/getUsers              # URL에 동사
/api/v1/user                  # 단수형 (복수형을 쓸 것)
/api/v1/team_members          # URL에 snake_case
/api/v1/users/123/getOrders   # 중첩 리소스에 동사
```

## HTTP 메서드와 상태 코드

### 메서드 의미

| 메서드 | 멱등성 | 안전성 | 용도 |
|--------|-----------|------|---------|
| GET | 예 | 예 | 리소스 조회 |
| POST | 아니오 | 아니오 | 리소스 생성, 동작 실행 |
| PUT | 예 | 아니오 | 리소스 전체 교체 |
| PATCH | 아니오* | 아니오 | 리소스 부분 수정 |
| DELETE | 예 | 아니오 | 리소스 삭제 |

*PATCH도 구현에 따라 멱등하게 만들 수 있다

### 상태 코드 참조

```
# 성공
200 OK                    — GET, PUT, PATCH (응답 본문 있음)
201 Created               — POST (Location 헤더 포함)
204 No Content            — DELETE, PUT (응답 본문 없음)

# 클라이언트 오류
400 Bad Request           — 검증 실패, 깨진 JSON
401 Unauthorized          — 인증 없음 또는 잘못된 인증
403 Forbidden             — 인증됐지만 권한 없음
404 Not Found             — 리소스가 없음
409 Conflict              — 중복, 상태 충돌 (CreditBook: 동시 차감 충돌)
422 Unprocessable Entity  — 형식은 맞지만 의미상 잘못된 데이터 (CreditBook: 잔액 부족)
429 Too Many Requests     — 레이트 리밋 초과

# 서버 오류
500 Internal Server Error — 예상치 못한 실패 (세부 정보 절대 노출 금지)
502 Bad Gateway           — 상위 서비스 실패
503 Service Unavailable   — 일시적 과부하, Retry-After 포함
```

### 흔한 실수

```
# 나쁨: 모든 응답을 200으로
{ "status": 200, "success": false, "error": "Not found" }

# 좋음: HTTP 상태 코드를 의미대로 사용
HTTP/1.1 404 Not Found
{ "error": { "code": "not_found", "message": "User not found" } }

# 나쁨: 검증 오류에 500
# 좋음: 필드별 상세와 함께 400 또는 422

# 나쁨: 생성에 200
# 좋음: Location 헤더와 함께 201
HTTP/1.1 201 Created
Location: /api/v1/users/abc-123
```

## 응답 형식

### 성공 응답

```json
{
  "data": {
    "id": "abc-123",
    "email": "alice@example.com",
    "name": "Alice",
    "created_at": "2025-01-15T10:30:00Z"
  }
}
```

### 목록 응답 (페이지네이션 포함)

```json
{
  "data": [
    { "id": "abc-123", "name": "Alice" },
    { "id": "def-456", "name": "Bob" }
  ],
  "meta": {
    "total": 142,
    "page": 1,
    "per_page": 20,
    "total_pages": 8
  },
  "links": {
    "self": "/api/v1/users?page=1&per_page=20",
    "next": "/api/v1/users?page=2&per_page=20",
    "last": "/api/v1/users?page=8&per_page=20"
  }
}
```

### 오류 응답

```json
{
  "error": {
    "code": "validation_error",
    "message": "Request validation failed",
    "details": [
      {
        "field": "email",
        "message": "Must be a valid email address",
        "code": "invalid_format"
      },
      {
        "field": "age",
        "message": "Must be between 0 and 150",
        "code": "out_of_range"
      }
    ]
  }
}
```

### 응답 봉투(envelope) 방식

```typescript
// 방식 A: data로 감싸는 봉투 (공개 API에 권장)
interface ApiResponse<T> {
  data: T;
  meta?: PaginationMeta;
  links?: PaginationLinks;
}

interface ApiError {
  error: {
    code: string;
    message: string;
    details?: FieldError[];
  };
}

// 방식 B: 평면 응답 (단순, 내부 API에 흔함)
// 성공: 리소스를 그대로 반환
// 오류: 오류 객체 반환
// HTTP 상태 코드로 구분
```

## 페이지네이션

### 오프셋 방식 (단순)

```
GET /api/v1/users?page=2&per_page=20

# 구현
SELECT * FROM users
ORDER BY created_at DESC
LIMIT 20 OFFSET 20;
```

**장점:** 구현이 쉽고 "N페이지로 이동"을 지원한다
**단점:** 오프셋이 크면 느리고(OFFSET 100000), 동시에 삽입되면 결과가 어긋난다

### 커서 방식 (확장성)

```
GET /api/v1/users?cursor=eyJpZCI6MTIzfQ&limit=20

# 구현
SELECT * FROM users
WHERE id > :cursor_id
ORDER BY id ASC
LIMIT 21;  -- 다음 페이지 여부를 알기 위해 하나 더 가져온다
```

```json
{
  "data": [...],
  "meta": {
    "has_next": true,
    "next_cursor": "eyJpZCI6MTQzfQ"
  }
}
```

**장점:** 위치와 상관없이 성능이 일정하고, 동시 삽입에도 안정적이다
**단점:** 임의 페이지로 이동할 수 없고, 커서 값이 불투명하다

### 언제 무엇을 쓰나

| 용도 | 페이지네이션 방식 |
|----------|----------------|
| 관리자 대시보드, 작은 데이터(1만 건 미만) | 오프셋 |
| 무한 스크롤, 피드, 큰 데이터 | 커서 |
| 공개 API | 커서(기본) + 오프셋(선택) |
| 검색 결과 | 오프셋 (사용자가 페이지 번호를 기대) |

## 필터링, 정렬, 검색

### 필터링

```
# 단순 동등 비교
GET /api/v1/orders?status=active&customer_id=abc-123

# 비교 연산자 (대괄호 표기)
GET /api/v1/products?price[gte]=10&price[lte]=100
GET /api/v1/orders?created_at[after]=2025-01-01

# 여러 값 (쉼표 구분)
GET /api/v1/products?category=electronics,clothing

# 중첩 필드 (점 표기)
GET /api/v1/orders?customer.country=US
```

### 정렬

```
# 단일 필드 (내림차순은 - 접두사)
GET /api/v1/products?sort=-created_at

# 여러 필드 (쉼표 구분)
GET /api/v1/products?sort=-featured,price,-created_at
```

### 전문 검색

```
# 검색 쿼리 파라미터
GET /api/v1/products?q=wireless+headphones

# 필드별 검색
GET /api/v1/users?email=alice
```

### 필드 선택 (Sparse Fieldsets)

```
# 지정한 필드만 반환 (응답 크기 감소)
GET /api/v1/users?fields=id,name,email
GET /api/v1/orders?fields=id,total,status&include=customer.name
```

## 인증과 인가

### 토큰 기반 인증

```
# Authorization 헤더의 Bearer 토큰
GET /api/v1/users
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...

# API 키 (서버 간 통신용)
GET /api/v1/data
X-API-Key: sk_live_abc123
```

### 인가 패턴

```typescript
// 리소스 수준: 소유권 확인
app.get("/api/v1/orders/:id", async (req, res) => {
  const order = await Order.findById(req.params.id);
  if (!order) return res.status(404).json({ error: { code: "not_found" } });
  if (order.userId !== req.user.id) return res.status(403).json({ error: { code: "forbidden" } });
  return res.json({ data: order });
});

// 역할 기반: 권한 확인
app.delete("/api/v1/users/:id", requireRole("admin"), async (req, res) => {
  await User.delete(req.params.id);
  return res.status(204).send();
});
```

## 레이트 리밋

### 헤더

```
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1640000000

# 초과했을 때
HTTP/1.1 429 Too Many Requests
Retry-After: 60
{
  "error": {
    "code": "rate_limit_exceeded",
    "message": "Rate limit exceeded. Try again in 60 seconds."
  }
}
```

### 레이트 리밋 등급

| 등급 | 한도 | 기준 | 용도 |
|------|-------|--------|----------|
| 익명 | 분당 30 | IP별 | 공개 엔드포인트 |
| 인증됨 | 분당 100 | 사용자별 | 일반 API 접근 |
| 프리미엄 | 분당 1000 | API 키별 | 유료 API 요금제 |
| 내부 | 분당 10000 | 서비스별 | 서비스 간 통신 |

## 버저닝

### URL 경로 버저닝 (권장)

```
/api/v1/users
/api/v2/users
```

**장점:** 명시적이고, 라우팅이 쉽고, 캐시 가능
**단점:** 버전마다 URL이 바뀐다

### 헤더 버저닝

```
GET /api/users
Accept: application/vnd.myapp.v2+json
```

**장점:** URL이 깔끔하다
**단점:** 테스트가 어렵고 빠뜨리기 쉽다

### 버저닝 전략

```
1. /api/v1/ 로 시작한다 — 필요해지기 전까지 버전을 올리지 않는다
2. 활성 버전은 최대 2개 (현재 + 이전)
3. 폐기 일정:
   - 폐기 예고 (공개 API는 6개월 전)
   - Sunset 헤더 추가: Sunset: Sat, 01 Jan 2026 00:00:00 GMT
   - 폐기일 이후 410 Gone 반환
4. 호환되는 변경은 새 버전이 필요 없다:
   - 응답에 새 필드 추가
   - 선택적 쿼리 파라미터 추가
   - 새 엔드포인트 추가
5. 호환이 깨지는 변경은 새 버전이 필요하다:
   - 필드 삭제·이름 변경
   - 필드 타입 변경
   - URL 구조 변경
   - 인증 방식 변경
```

## 구현 패턴 (Spring Boot)

### 컨트롤러 + Bean Validation + DTO

```java
@RestController
@RequestMapping("/api/v1/prepaid-accounts/{accountId}/charges")
class ChargeController {
  private final ChargeService chargeService;

  ChargeController(ChargeService chargeService) {
    this.chargeService = chargeService;
  }

  @PostMapping
  ResponseEntity<ChargeResponse> charge(
      @PathVariable UUID accountId,
      @Valid @RequestBody ChargeRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    ChargeResult result = chargeService.charge(accountId, request.amount(), idempotencyKey);
    return ResponseEntity
        .created(URI.create("/api/v1/ledger-entries/" + result.entryId()))
        .body(ChargeResponse.from(result));
  }
}

public record ChargeRequest(
    @NotNull @Positive @DecimalMax("300000") BigDecimal amount,
    @Size(max = 200) String memo) {}

public record ChargeResponse(UUID entryId, BigDecimal balanceAfter, OffsetDateTime performedAt) {
  static ChargeResponse from(ChargeResult result) {
    return new ChargeResponse(result.entryId(), result.balanceAfter(), result.performedAt());
  }
}
```

### 표준 에러 응답

```java
@RestControllerAdvice
class GlobalExceptionHandler {
  @ExceptionHandler(InsufficientBalanceException.class)
  ResponseEntity<ErrorResponse> handle(InsufficientBalanceException ex) {
    return ResponseEntity.status(422).body(ErrorResponse.of("insufficient_balance", ex.getMessage()));
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ResponseEntity<ErrorResponse> handleConflict(ObjectOptimisticLockingFailureException ex) {
    return ResponseEntity.status(409).body(ErrorResponse.of("balance_conflict", "잔액이 동시에 변경됐습니다. 다시 시도하세요."));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage())
        .collect(Collectors.joining(", "));
    return ResponseEntity.status(422).body(ErrorResponse.of("validation_error", message));
  }
}
```

## API 설계 체크리스트

새 엔드포인트를 배포하기 전에:

- [ ] 리소스 URL이 네이밍 규칙을 따름 (복수형, kebab-case, 동사 없음)
- [ ] 올바른 HTTP 메서드 사용 (조회는 GET, 생성은 POST 등)
- [ ] 상황에 맞는 상태 코드 반환 (모든 걸 200으로 처리하지 않음)
- [ ] `@Valid` + Bean Validation으로 입력 검증
- [ ] 에러 응답이 일관된 형식(코드+메시지)을 따름
- [ ] 목록 엔드포인트에 페이지네이션 구현 (`ledger_entries`는 `seq` 커서 기반 우선)
- [ ] 인증 필요 여부 명시 (JWT 필요/공개 엔드포인트 구분)
- [ ] 인가 확인 (STAFF가 ADMIN 전용 리소스에 접근 못 함)
- [ ] 잔액 변경 엔드포인트는 `Idempotency-Key` 헤더를 받아 `ledger_entries.idempotency_key`로 중복 방지
- [ ] 응답에 스택 트레이스·SQL 에러 등 내부 정보가 노출되지 않음
- [ ] 엔티티가 아니라 DTO(`*Request`/`*Response`)로 응답
- [ ] 컨트롤러가 아니라 도메인/애플리케이션 계층에서 비즈니스 규칙을 검증
