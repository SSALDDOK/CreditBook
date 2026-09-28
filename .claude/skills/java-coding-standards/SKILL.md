---
name: java-coding-standards
description: "Java 17+/Spring Boot 코딩 표준 — 네이밍, 불변성, Optional, 스트림, 예외, 제네릭, 패키지 구조. CreditBook은 Spring Boot만 쓰므로 원본 ECC 스킬의 Quarkus/CDI/리액티브 섹션은 제거했다. Java 코드 작성·리뷰, 네이밍/불변성/예외 처리 컨벤션 점검 시 사용."
metadata:
  origin: ECC (Quarkus/CDI/reactive sections removed — Spring Boot only)
---

# Java Coding Standards (Spring Boot)

읽기 쉽고 유지보수 가능한 Java 17+ 코드를 위한 표준.

## When to Use

- Spring Boot 프로젝트에서 Java 코드 작성/리뷰
- 네이밍, 불변성, 예외 처리 컨벤션 점검
- record, sealed class, 패턴 매칭(Java 17+) 사용
- Optional, 스트림, 제네릭 사용 리뷰
- 패키지/프로젝트 구조 설계

## Core Principles

- 영리함보다 명료함
- 기본적으로 불변, 공유 가변 상태 최소화
- 의미 있는 예외로 빠르게 실패
- 일관된 네이밍과 패키지 구조

## Naming

```java
// PASS: 클래스/레코드: PascalCase
public class ChargeService {}
public record Money(BigDecimal amount) {}

// PASS: 메서드/필드: camelCase
private final PrepaidAccountRepository prepaidAccountRepository;
public PrepaidAccount findByCustomerId(UUID customerId) {}

// PASS: 상수: UPPER_SNAKE_CASE
private static final int MAX_PAGE_SIZE = 100;

// PASS: REST 컨트롤러는 *Controller
public class ChargeController {}
```

## Immutability

```java
// PASS: record와 final 필드를 우선한다
public record LedgerEntryResponse(UUID id, BigDecimal amount, LedgerEntryType type) {}

public class PrepaidAccount {
  private final UUID id;
  private BigDecimal balance;   // 상태 변경은 charge()/use() 같은 도메인 메서드로만, setter 없음
}
```

## Optional Usage

```java
// PASS: find* 메서드는 Optional을 반환한다
Optional<PrepaidAccount> account = prepaidAccountRepository.findByCustomerId(customerId);

// PASS: get() 대신 map/flatMap, orElseThrow
return account
    .map(PrepaidAccountResponse::from)
    .orElseThrow(() -> new AccountNotFoundException(customerId));
```

## Streams Best Practices

```java
// PASS: 변환에는 스트림을, 파이프라인은 짧게 유지
List<String> names = customers.stream()
    .map(Customer::name)
    .filter(Objects::nonNull)
    .toList();

// FAIL: 복잡하게 중첩된 스트림은 피한다 — 명료함을 위해 루프를 선택할 것
```

## Dependency Injection

```java
// PASS: 생성자 주입 (필드 @Autowired보다 우선)
@Service
public class ChargeService {
  private final PrepaidAccountRepository prepaidAccountRepository;

  public ChargeService(PrepaidAccountRepository prepaidAccountRepository) {
    this.prepaidAccountRepository = prepaidAccountRepository;
  }
}

// FAIL: 필드에 @Autowired
@Autowired
private PrepaidAccountRepository prepaidAccountRepository; // 생성자 주입을 쓸 것
```

## Exceptions

- 도메인 에러는 unchecked exception으로, 기술적 예외는 컨텍스트를 담아 감싼다
- 도메인 전용 예외를 만든다 (예: `InsufficientBalanceException`)
- 중앙에서 로깅/rethrow하는 경우가 아니면 광범위한 `catch (Exception ex)`를 피한다

```java
throw new InsufficientBalanceException(accountId, requestedAmount, currentBalance);
```

### Centralised Exception Handling

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(InsufficientBalanceException.class)
  public ResponseEntity<ErrorResponse> handle(InsufficientBalanceException ex) {
    return ResponseEntity.status(422).body(ErrorResponse.from(ex));
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<ErrorResponse> handleConflict(ObjectOptimisticLockingFailureException ex) {
    return ResponseEntity.status(409).body(ErrorResponse.of("잔액이 동시에 변경됐습니다. 다시 시도하세요."));
  }
}
```

## Generics and Type Safety

- raw type을 피하고 제네릭 파라미터를 명시한다
- 재사용 유틸리티는 bounded generic을 우선한다

```java
public <T extends Identifiable> Map<UUID, T> indexById(Collection<T> items) { ... }
```

## Project Structure

CreditBook은 계층이 아니라 도메인을 최상위로 둔다 (자세한 내용은 프로젝트 루트 `CLAUDE.md` 참고):

```
src/main/java/com/creditbook/
  global/{config,error,security}/
  customer/{domain,application,controller,infrastructure}/
  prepaid/{domain,application,controller,infrastructure}/
  auth/{domain,application,controller,infrastructure}/
src/main/resources/
  application.yml
  db/migration/          # Flyway V{n}__description.sql
src/test/java/... (main 구조를 미러링)
```

## Formatting and Style

- 스페이스는 프로젝트 표준(2 또는 4)을 일관되게 사용
- 파일당 public 최상위 타입 하나
- 메서드는 짧고 집중되게, 필요하면 헬퍼로 추출
- 멤버 순서: 상수, 필드, 생성자, public 메서드, protected, private

## Code Smells to Avoid

- 긴 파라미터 목록 → DTO/builder 사용
- 깊은 중첩 → 조기 반환(early return)
- 매직 넘버 → 이름 있는 상수
- 정적 가변 상태 → 의존성 주입 선호
- 무음 catch 블록 → 로그를 남기고 조치하거나 rethrow

## Logging

```java
// SLF4J
private static final Logger log = LoggerFactory.getLogger(ChargeService.class);
log.info("charge_requested accountId={} amount={}", accountId, amount);
log.error("charge_failed accountId={}", accountId, ex);
```
고객 전화번호, 비밀번호 해시 등 민감정보는 로그에 남기지 않는다.

## Null Handling

- `@Nullable`은 불가피할 때만, 그 외에는 `@NonNull`
- 입력값에는 Bean Validation(`@NotNull`, `@NotBlank`) 적용

## Configuration

```java
// @ConfigurationProperties
@ConfigurationProperties(prefix = "creditbook.charge")
public record ChargeProperties(BigDecimal maxAmount) {}
```
`creditbook.charge.max-amount` 같은 정책값은 이렇게 설정으로 바인딩하고, 코드에 상수로 하드코딩하지 않는다.

## Testing Expectations

- JUnit 5 + AssertJ(fluent assertion)
- Mockito로 목킹, 부분 목(partial mock)은 가급적 피함
- 숨은 sleep 없이 결정적인(deterministic) 테스트
- `@WebMvcTest`로 컨트롤러 슬라이스, `@DataJpaTest`로 리포지토리 슬라이스
- `@SpringBootTest`는 진짜 통합 테스트에만 사용 (도메인 단위 테스트에는 쓰지 않는다 — CreditBook 테스트 규칙)
- `@MockBean`으로 Spring 컨텍스트의 빈을 교체

```java
// 컨트롤러 테스트
@WebMvcTest(ChargeController.class)
class ChargeControllerTest {
  @Autowired MockMvc mockMvc;
  @MockBean ChargeService chargeService;
}

// 서비스 단위 테스트 (스프링 컨텍스트 없음)
@ExtendWith(MockitoExtension.class)
class ChargeServiceTest {
  @Mock PrepaidAccountRepository prepaidAccountRepository;
  @InjectMocks ChargeService chargeService;
}
```

**Remember**: 코드는 의도가 분명하고, 타입이 명확하고, 관찰 가능해야 한다. 검증되지 않은 마이크로 최적화보다 유지보수성을 우선한다.
