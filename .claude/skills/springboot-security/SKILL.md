---
name: springboot-security
description: Java Spring Boot 서비스의 Spring Security 모범 사례 — 인증(authn)/인가(authz), 입력 검증, CSRF, 시크릿, 보안 헤더, 레이트 리밋, 의존성 보안. Spring Security 인증·인가, 검증, CSRF, 시크릿, 헤더, 레이트 리밋을 구현하거나 리뷰할 때 사용.
metadata:
  origin: ECC
---

# Spring Boot 보안 리뷰

인증을 추가하거나, 입력을 처리하거나, 엔드포인트를 만들거나, 시크릿을 다룰 때 참고한다.

## 언제 쓰는가

- 인증 추가 (JWT, OAuth2, 세션 기반)
- 인가 구현 (@PreAuthorize, 역할 기반 접근)
- 사용자 입력 검증 (Bean Validation, 커스텀 검증기)
- CORS, CSRF, 보안 헤더 설정
- 시크릿 관리 (Vault, 환경변수)
- 레이트 리밋이나 무차별 대입(brute-force) 방어 추가
- 의존성 CVE 스캔

## 인증

- 무상태(stateless) JWT, 또는 폐기 목록이 있는 불투명 토큰을 쓴다
- 세션 쿠키는 `httpOnly`, `Secure`, `SameSite=Strict`로 설정한다
- 토큰은 `OncePerRequestFilter` 또는 리소스 서버로 검증한다

```java
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
  private final JwtService jwtService;

  public JwtAuthFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith("Bearer ")) {
      String token = header.substring(7);
      Authentication auth = jwtService.authenticate(token);
      SecurityContextHolder.getContext().setAuthentication(auth);
    }
    chain.doFilter(request, response);
  }
}
```

## 인가

- 메서드 보안 활성화: `@EnableMethodSecurity`
- `@PreAuthorize("hasRole('ADMIN')")` 또는 `@PreAuthorize("@authz.canEdit(#id)")`를 쓴다 (CreditBook: 고객 비활성화는 ADMIN만, REQ-16)
- 기본은 거부. 필요한 범위만 연다

```java
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  @PreAuthorize("hasRole('ADMIN')")
  @GetMapping("/users")
  public List<UserDto> listUsers() {
    return userService.findAll();
  }

  @PreAuthorize("@authz.isOwner(#id, authentication)")
  @DeleteMapping("/users/{id}")
  public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
    userService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
```

## 입력 검증

- 컨트롤러에서 `@Valid`와 Bean Validation을 쓴다
- DTO에 제약을 건다: `@NotBlank`, `@Email`, `@Size`, 커스텀 검증기
- HTML은 렌더링 전에 허용 목록(whitelist) 방식으로 정제한다

```java
// 나쁨: 검증 없음
@PostMapping("/users")
public User createUser(@RequestBody UserDto dto) {
  return userService.create(dto);
}

// 좋음: 검증된 DTO
public record CreateUserDto(
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Email String email,
    @NotNull @Min(0) @Max(150) Integer age
) {}

@PostMapping("/users")
public ResponseEntity<UserDto> createUser(@Valid @RequestBody CreateUserDto dto) {
  return ResponseEntity.status(HttpStatus.CREATED)
      .body(userService.create(dto));
}
```

## SQL 인젝션 방지

- Spring Data 리포지토리나 파라미터 바인딩 쿼리를 쓴다
- 네이티브 쿼리는 `:param` 바인딩을 쓰고, 문자열을 이어 붙이지 않는다

```java
// 나쁨: 네이티브 쿼리에 문자열 연결
@Query(value = "SELECT * FROM users WHERE name = '" + name + "'", nativeQuery = true)

// 좋음: 파라미터 바인딩 네이티브 쿼리
@Query(value = "SELECT * FROM users WHERE name = :name", nativeQuery = true)
List<User> findByName(@Param("name") String name);

// 좋음: Spring Data 파생 쿼리 (자동 바인딩)
List<User> findByEmailAndActiveTrue(String email);
```

## 비밀번호 인코딩

- 비밀번호는 항상 BCrypt나 Argon2로 해시한다 — 평문 저장 금지
- 직접 해시하지 말고 `PasswordEncoder` 빈을 쓴다

```java
@Bean
public PasswordEncoder passwordEncoder() {
  return new BCryptPasswordEncoder(12); // 비용 계수 12
}

// 서비스에서
public User register(CreateUserDto dto) {
  String hashedPassword = passwordEncoder.encode(dto.password());
  return userRepository.save(new User(dto.email(), hashedPassword));
}
```

## CSRF 방어

- 브라우저 세션 기반 앱은 CSRF를 켜 두고, 폼·헤더에 토큰을 넣는다
- Bearer 토큰을 쓰는 순수 API는 CSRF를 끄고 무상태 인증에 의존한다

```java
http
  .csrf(csrf -> csrf.disable())
  .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
```

## 시크릿 관리

- 소스에 시크릿을 두지 않는다. 환경변수나 vault에서 읽는다
- `application.yml`에는 자격증명을 두지 않고 플레이스홀더를 쓴다
- 토큰과 DB 자격증명은 주기적으로 교체한다

```yaml
# 나쁨: application.yml에 하드코딩
spring:
  datasource:
    password: mySecretPassword123

# 좋음: 환경변수 플레이스홀더
spring:
  datasource:
    password: ${DB_PASSWORD}

# 좋음: Spring Cloud Vault 연동
spring:
  cloud:
    vault:
      uri: https://vault.example.com
      token: ${VAULT_TOKEN}
```

## 보안 헤더

```java
http
  .headers(headers -> headers
    .contentSecurityPolicy(csp -> csp
      .policyDirectives("default-src 'self'"))
    .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
    .xssProtection(Customizer.withDefaults())
    .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
```

## CORS 설정

- CORS는 컨트롤러별이 아니라 보안 필터 수준에서 설정한다
- 허용 출처를 제한한다 — 운영에서 `*` 금지

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
  CorsConfiguration config = new CorsConfiguration();
  config.setAllowedOrigins(List.of("https://app.example.com"));
  config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
  config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
  config.setAllowCredentials(true);
  config.setMaxAge(3600L);

  UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
  source.registerCorsConfiguration("/api/**", config);
  return source;
}

// SecurityFilterChain에서:
http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
```

## 레이트 리밋

- 비용이 큰 엔드포인트에 Bucket4j나 게이트웨이 수준 제한을 건다
- 급증은 로그와 알림으로 잡고, 재시도 힌트와 함께 429를 반환한다

```java
// Bucket4j로 엔드포인트별 레이트 리밋
@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  private Bucket createBucket() {
    return Bucket.builder()
        .addLimit(Bandwidth.classic(100, Refill.intervally(100, Duration.ofMinutes(1))))
        .build();
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String clientIp = request.getRemoteAddr();
    Bucket bucket = buckets.computeIfAbsent(clientIp, k -> createBucket());

    if (bucket.tryConsume(1)) {
      chain.doFilter(request, response);
    } else {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.getWriter().write("{\"error\": \"Rate limit exceeded\"}");
    }
  }
}
```

## 의존성 보안

- CI에서 OWASP Dependency Check / Snyk를 돌린다
- Spring Boot와 Spring Security는 지원 중인 버전을 유지한다
- 알려진 CVE가 있으면 빌드를 실패시킨다

## 로깅과 개인정보(PII)

- 시크릿, 토큰, 비밀번호, 카드번호 전체를 로그에 남기지 않는다 (CreditBook: 고객 이름·연락처도 남기지 않는다)
- 민감 필드는 가리고(redact), 구조화된 JSON 로깅을 쓴다

## 파일 업로드

- 크기, 콘텐츠 타입, 확장자를 검증한다
- 웹 루트 밖에 저장하고, 필요하면 악성 코드 검사를 한다

## 릴리스 전 체크리스트

- [ ] 인증 토큰이 올바르게 검증·만료된다
- [ ] 민감한 경로마다 인가 검사가 있다
- [ ] 모든 입력이 검증·정제된다
- [ ] 문자열 연결로 만든 SQL이 없다
- [ ] 앱 유형에 맞는 CSRF 설정이다
- [ ] 시크릿이 외부화돼 있고 커밋된 것이 없다
- [ ] 보안 헤더가 설정돼 있다
- [ ] API에 레이트 리밋이 있다
- [ ] 의존성을 스캔했고 최신이다
- [ ] 로그에 민감정보가 없다

**기억할 것**: 기본은 거부, 입력은 검증, 권한은 최소로, 설정으로 먼저 안전하게.
