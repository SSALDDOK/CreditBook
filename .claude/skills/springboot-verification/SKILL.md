---
name: springboot-verification
description: "Spring Boot 프로젝트 검증 루프 — 빌드, 정적 분석, 테스트와 커버리지, 보안 스캔, diff 리뷰를 릴리스나 PR 전에 순서대로 돌린다."
metadata:
  origin: ECC
---

# Spring Boot 검증 루프

PR 전, 큰 변경 후, 배포 전에 실행한다.

## 언제 쓰는가

- Spring Boot 서비스의 PR을 열기 전
- 큰 리팩터링이나 의존성 업그레이드 후
- 스테이징·운영 배포 전 검증
- 빌드 → 린트 → 테스트 → 보안 스캔 전체 파이프라인 실행
- 테스트 커버리지가 기준을 넘는지 확인

## 1단계: 빌드

```bash
mvn -T 4 clean verify -DskipTests
# or
./gradlew clean assemble -x test
```

빌드가 실패하면 멈추고 고친다.

## 2단계: 정적 분석

Maven (자주 쓰는 플러그인):
```bash
mvn -T 4 spotbugs:check pmd:check checkstyle:check
```

Gradle (설정돼 있다면):
```bash
./gradlew checkstyleMain pmdMain spotbugsMain
```

## 3단계: 테스트 + 커버리지

```bash
mvn -T 4 test
mvn jacoco:report   # 커버리지 80% 이상인지 확인
# or
./gradlew test jacocoTestReport
```

보고 항목:
- 전체 테스트 수, 성공/실패
- 커버리지 % (라인/분기)

### 단위 테스트

의존성을 목으로 대체해 서비스 로직만 따로 검증한다:

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private UserRepository userRepository;
  @InjectMocks private UserService userService;

  @Test
  void createUser_validInput_returnsUser() {
    var dto = new CreateUserDto("Alice", "alice@example.com");
    var expected = new User(1L, "Alice", "alice@example.com");
    when(userRepository.save(any(User.class))).thenReturn(expected);

    var result = userService.create(dto);

    assertThat(result.name()).isEqualTo("Alice");
    verify(userRepository).save(any(User.class));
  }

  @Test
  void createUser_duplicateEmail_throwsException() {
    var dto = new CreateUserDto("Alice", "existing@example.com");
    when(userRepository.existsByEmail(dto.email())).thenReturn(true);

    assertThatThrownBy(() -> userService.create(dto))
        .isInstanceOf(DuplicateEmailException.class);
  }
}
```

### Testcontainers 통합 테스트

H2가 아니라 실제 데이터베이스로 검증한다:

```java
@SpringBootTest
@Testcontainers
class UserRepositoryIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("testdb");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private UserRepository userRepository;

  @Test
  void findByEmail_existingUser_returnsUser() {
    userRepository.save(new User("Alice", "alice@example.com"));

    var found = userRepository.findByEmail("alice@example.com");

    assertThat(found).isPresent();
    assertThat(found.get().getName()).isEqualTo("Alice");
  }
}
```

### MockMvc API 테스트

컨트롤러 계층을 웹 슬라이스 컨텍스트로 검증한다:

```java
@WebMvcTest(UserController.class)
class UserControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;   // Boot 4: @MockBean 삭제됨

  @Test
  void createUser_validInput_returns201() throws Exception {
    var user = new UserDto(1L, "Alice", "alice@example.com");
    when(userService.create(any())).thenReturn(user);

    mockMvc.perform(post("/api/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Alice", "email": "alice@example.com"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Alice"));
  }

  @Test
  void createUser_invalidEmail_returns400() throws Exception {
    mockMvc.perform(post("/api/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Alice", "email": "not-an-email"}
                """))
        .andExpect(status().isBadRequest());
  }
}
```

## 4단계: 보안 스캔

```bash
# 의존성 CVE
mvn org.owasp:dependency-check-maven:check
# or
./gradlew dependencyCheckAnalyze

# 소스 코드의 시크릿
grep -rn "password\s*=\s*\"" src/ --include="*.java" --include="*.yml" --include="*.properties"
grep -rn "sk-\|api_key\|secret" src/ --include="*.java" --include="*.yml"

# 시크릿 (git 이력)
git secrets --scan  # 설정돼 있다면
```

### 자주 나오는 보안 지적 사항

```
# System.out.println 확인 (로거를 쓸 것)
grep -rn "System\.out\.print" src/main/ --include="*.java"

# 응답에 예외 메시지를 그대로 넣는지 확인
grep -rn "e\.getMessage()" src/main/ --include="*.java"

# 와일드카드 CORS 확인
grep -rn "allowedOrigins.*\*" src/main/ --include="*.java"
```

## 5단계: 린트/포맷 (선택 게이트)

```bash
mvn spotless:apply   # Spotless 플러그인을 쓴다면
./gradlew spotlessApply
```

## 6단계: diff 리뷰

```bash
git diff --stat
git diff
```

체크리스트:
- 디버깅 로그가 남아 있지 않다 (`System.out`, 가드 없는 `log.debug`)
- 오류 메시지와 HTTP 상태 코드가 의미 있다
- 필요한 곳에 트랜잭션과 검증이 있다
- 설정 변경이 문서화됐다

## 보고 양식

```
검증 보고서
===========
빌드:      [통과/실패]
정적 분석: [통과/실패] (spotbugs/pmd/checkstyle)
테스트:    [통과/실패] (X/Y 통과, 커버리지 Z%)
보안:      [통과/실패] (CVE N건)
diff:      [파일 X개 변경]

종합:      [준비됨 / 준비 안 됨]

고칠 항목:
1. ...
2. ...
```

## 반복 실행

- 큰 변경이 있을 때, 또는 긴 세션에서는 30–60분마다 다시 돌린다
- 빠른 피드백용 짧은 루프: `./gradlew test` + 정적 분석

**기억할 것**: 늦게 놀라는 것보다 빨리 아는 게 낫다. 게이트는 엄격하게 — 운영 시스템에서는 경고도 결함으로 취급한다.
