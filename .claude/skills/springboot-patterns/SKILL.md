---
name: springboot-patterns
description: Spring Boot 아키텍처 패턴 — REST API 설계, 계층형 서비스, 데이터 접근, 캐시, 비동기 처리, 로깅. Java Spring Boot 백엔드 작업 전반에 사용. Spring Boot 백엔드의 REST 계층·서비스·데이터 접근·캐시·비동기 작업을 만들거나 리뷰할 때 사용.
metadata:
  origin: ECC
---

# Spring Boot 개발 패턴

확장 가능하고 운영 수준인 서비스를 위한 Spring Boot 아키텍처·API 패턴.

> CreditBook 주의: 아래 예시의 `MarketController → MarketService → MarketRepository`는 계층형 예시일 뿐이다. 이 프로젝트는 도메인을 최상위로 두는 DDD 구조이고, 잔액 계산은 반드시 `PrepaidAccount` 안에 둔다 (CLAUDE.md 패키지 구조 참고).

## 언제 쓰는가

- Spring MVC 또는 WebFlux로 REST API 구축
- controller → service → repository 계층 구성
- Spring Data JPA, 캐시, 비동기 처리 설정
- 검증, 예외 처리, 페이지네이션 추가
- dev/staging/production 환경별 프로필 설정
- Spring Events나 Kafka로 이벤트 기반 패턴 구현

## REST API 구조

```java
@RestController
@RequestMapping("/api/markets")
@Validated
class MarketController {
  private final MarketService marketService;

  MarketController(MarketService marketService) {
    this.marketService = marketService;
  }

  @GetMapping
  ResponseEntity<Page<MarketResponse>> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Page<Market> markets = marketService.list(PageRequest.of(page, size));
    return ResponseEntity.ok(markets.map(MarketResponse::from));
  }

  @PostMapping
  ResponseEntity<MarketResponse> create(@Valid @RequestBody CreateMarketRequest request) {
    Market market = marketService.create(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(MarketResponse.from(market));
  }
}
```

## 리포지토리 패턴 (Spring Data JPA)

```java
public interface MarketRepository extends JpaRepository<MarketEntity, Long> {
  @Query("select m from MarketEntity m where m.status = :status order by m.volume desc")
  List<MarketEntity> findActive(@Param("status") MarketStatus status, Pageable pageable);
}
```

## 트랜잭션이 있는 서비스 계층

```java
@Service
public class MarketService {
  private final MarketRepository repo;

  public MarketService(MarketRepository repo) {
    this.repo = repo;
  }

  @Transactional
  public Market create(CreateMarketRequest request) {
    MarketEntity entity = MarketEntity.from(request);
    MarketEntity saved = repo.save(entity);
    return Market.from(saved);
  }
}
```

## DTO와 검증

```java
public record CreateMarketRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 2000) String description,
    @NotNull @FutureOrPresent Instant endDate,
    @NotEmpty List<@NotBlank String> categories) {}

public record MarketResponse(Long id, String name, MarketStatus status) {
  static MarketResponse from(Market market) {
    return new MarketResponse(market.id(), market.name(), market.status());
  }
}
```

## 예외 처리

```java
@ControllerAdvice
class GlobalExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage())
        .collect(Collectors.joining(", "));
    return ResponseEntity.badRequest().body(ApiError.validation(message));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiError> handleAccessDenied() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("Forbidden"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleGeneric(Exception ex) {
    // 예상치 못한 오류는 스택트레이스와 함께 로그에만 남긴다 (응답에는 넣지 않는다)
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiError.of("Internal server error"));
  }
}
```

## 캐시

설정 클래스에 `@EnableCaching`이 필요하다.

```java
@Service
public class MarketCacheService {
  private final MarketRepository repo;

  public MarketCacheService(MarketRepository repo) {
    this.repo = repo;
  }

  @Cacheable(value = "market", key = "#id")
  public Market getById(Long id) {
    return repo.findById(id)
        .map(Market::from)
        .orElseThrow(() -> new EntityNotFoundException("Market not found"));
  }

  @CacheEvict(value = "market", key = "#id")
  public void evict(Long id) {}
}
```

## 비동기 처리

설정 클래스에 `@EnableAsync`가 필요하다.

```java
@Service
public class NotificationService {
  @Async
  public CompletableFuture<Void> sendAsync(Notification notification) {
    // 이메일/SMS 발송
    return CompletableFuture.completedFuture(null);
  }
}
```

## 로깅 (SLF4J)

```java
@Service
public class ReportService {
  private static final Logger log = LoggerFactory.getLogger(ReportService.class);

  public Report generate(Long marketId) {
    log.info("generate_report marketId={}", marketId);
    try {
      // 로직
    } catch (Exception ex) {
      log.error("generate_report_failed marketId={}", marketId, ex);
      throw ex;
    }
    return new Report();
  }
}
```

## 미들웨어 / 필터

```java
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    long start = System.currentTimeMillis();
    try {
      filterChain.doFilter(request, response);
    } finally {
      long duration = System.currentTimeMillis() - start;
      log.info("req method={} uri={} status={} durationMs={}",
          request.getMethod(), request.getRequestURI(), response.getStatus(), duration);
    }
  }
}
```

## 페이지네이션과 정렬

```java
PageRequest page = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
Page<Market> results = marketService.list(page);
```

## 오류에 강한 외부 호출 (재시도)

```java
public <T> T withRetry(Supplier<T> supplier, int maxRetries) {
  int attempts = 0;
  while (true) {
    try {
      return supplier.get();
    } catch (Exception ex) {
      attempts++;
      if (attempts >= maxRetries) {
        throw ex;
      }
      try {
        Thread.sleep((long) Math.pow(2, attempts) * 100L);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw ex;
      }
    }
  }
}
```

## 레이트 리밋 (필터 + Bucket4j)

**보안 주의**: `X-Forwarded-For` 헤더는 클라이언트가 위조할 수 있어 기본적으로 신뢰하지 않는다.
다음 조건을 모두 갖췄을 때만 포워딩 헤더를 쓴다:
1. 앱이 신뢰할 수 있는 리버스 프록시(nginx, AWS ALB 등, CreditBook은 Render) 뒤에 있다
2. `ForwardedHeaderFilter`를 빈으로 등록했다
3. `server.forward-headers-strategy=NATIVE` 또는 `FRAMEWORK`를 설정했다
4. 프록시가 `X-Forwarded-For` 헤더를 덧붙이지 않고 덮어쓰도록 설정돼 있다

`ForwardedHeaderFilter`가 제대로 설정되면 `request.getRemoteAddr()`가 포워딩 헤더에서 올바른 클라이언트 IP를
자동으로 돌려준다. 이 설정이 없으면 `request.getRemoteAddr()`를 그대로 쓴다 — 직접 연결된 IP로,
유일하게 믿을 수 있는 값이다.

```java
@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /*
   * 보안: 이 필터는 레이트 리밋 대상 클라이언트를 request.getRemoteAddr()로 식별한다.
   *
   * 리버스 프록시(nginx, AWS ALB 등) 뒤에 있다면, 정확한 클라이언트 IP를 얻기 위해
   * 반드시 스프링이 포워딩 헤더를 처리하도록 설정해야 한다:
   *
   * 1. application.yml에 server.forward-headers-strategy=NATIVE(클라우드 플랫폼) 또는
   *    FRAMEWORK를 설정한다
   * 2. FRAMEWORK 전략이면 ForwardedHeaderFilter를 등록한다:
   *
   *    @Bean
   *    ForwardedHeaderFilter forwardedHeaderFilter() {
   *        return new ForwardedHeaderFilter();
   *    }
   *
   * 3. 위조를 막기 위해 프록시가 X-Forwarded-For를 덧붙이지 않고 덮어쓰게 한다
   * 4. server.tomcat.remoteip.trusted-proxies 등 컨테이너에 맞는 신뢰 프록시를 설정한다
   *
   * 이 설정이 없으면 request.getRemoteAddr()는 클라이언트가 아니라 프록시 IP를 돌려준다.
   * X-Forwarded-For를 직접 읽지 않는다 — 신뢰 프록시 처리 없이는 쉽게 위조된다.
   */
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    // getRemoteAddr()는 ForwardedHeaderFilter가 설정돼 있으면 올바른 클라이언트 IP를,
    // 아니면 직접 연결 IP를 돌려준다. 프록시 설정 없이 X-Forwarded-For를 직접 신뢰하지 않는다.
    String clientIp = request.getRemoteAddr();

    Bucket bucket = buckets.computeIfAbsent(clientIp,
        k -> Bucket.builder()
            .addLimit(Bandwidth.classic(100, Refill.greedy(100, Duration.ofMinutes(1))))
            .build());

    if (bucket.tryConsume(1)) {
      filterChain.doFilter(request, response);
    } else {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    }
  }
}
```

## 백그라운드 작업

Spring의 `@Scheduled`를 쓰거나 큐(Kafka, SQS, RabbitMQ 등)와 연동한다. 핸들러는 멱등적이고 관찰 가능하게 만든다.

## 관측성(Observability)

- Logback 인코더로 구조화 로깅(JSON)
- 메트릭: Micrometer + Prometheus/OTel
- 트레이싱: Micrometer Tracing + OpenTelemetry 또는 Brave 백엔드

## 운영 기본값

- 생성자 주입을 쓰고 필드 주입은 피한다
- RFC 7807(9457) 오류 형식을 위해 `spring.mvc.problemdetails.enabled=true`를 켠다 (Spring Boot 3+)
- 부하에 맞게 HikariCP 풀 크기와 타임아웃을 설정한다
- 조회에는 `@Transactional(readOnly = true)`를 쓴다
- 필요한 곳에 `@NonNull`과 `Optional`로 null 안전성을 지킨다

**기억할 것**: 컨트롤러는 얇게, 서비스는 한 가지 일에 집중, 리포지토리는 단순하게, 오류는 한곳에서 처리한다. 유지보수성과 테스트 용이성을 우선한다.
