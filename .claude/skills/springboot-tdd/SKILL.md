---
name: springboot-tdd
description: JUnit 5, Mockito, MockMvc, Testcontainers, JaCoCo를 사용하는 Spring Boot 테스트 주도 개발(TDD). 기능 추가, 버그 수정, 리팩터링 시 사용.
metadata:
  origin: ECC
---

# Spring Boot TDD 워크플로

커버리지 80% 이상(단위 + 통합)을 목표로 하는 Spring Boot 서비스용 TDD 가이드.

## 언제 쓰는가

- 새 기능이나 엔드포인트
- 버그 수정이나 리팩터링
- 데이터 접근 로직이나 보안 규칙 추가

## 절차

1) 테스트를 먼저 작성한다 (실패해야 정상)
2) 통과할 만큼만 최소한으로 구현한다
3) 테스트가 초록인 상태에서 리팩터링한다
4) 커버리지를 강제한다 (JaCoCo)

## 단위 테스트 (JUnit 5 + Mockito)

```java
@ExtendWith(MockitoExtension.class)
class MarketServiceTest {
  @Mock MarketRepository repo;
  @InjectMocks MarketService service;

  @Test
  void createsMarket() {
    CreateMarketRequest req = new CreateMarketRequest("name", "desc", Instant.now(), List.of("cat"));
    when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Market result = service.create(req);

    assertThat(result.name()).isEqualTo("name");
    verify(repo).save(any());
  }
}
```

패턴:
- Arrange-Act-Assert (이 프로젝트에서는 Given-When-Then 주석 세 블록으로 쓴다)
- 부분 목(partial mock)을 피하고 명시적 스텁을 쓴다
- 변형 케이스는 `@ParameterizedTest`로 모은다

## 웹 계층 테스트 (MockMvc)

```java
@WebMvcTest(MarketController.class)
class MarketControllerTest {
  @Autowired MockMvc mockMvc;
  @MockitoBean MarketService marketService;   // Boot 4: @MockBean 삭제됨

  @Test
  void returnsMarkets() throws Exception {
    when(marketService.list(any())).thenReturn(Page.empty());

    mockMvc.perform(get("/api/markets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray());
  }
}
```

## 통합 테스트 (SpringBootTest)

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MarketIntegrationTest {
  @Autowired MockMvc mockMvc;

  @Test
  void createsMarket() throws Exception {
    mockMvc.perform(post("/api/markets")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"name":"Test","description":"Desc","endDate":"2030-01-01T00:00:00Z","categories":["general"]}
        """))
      .andExpect(status().isCreated());
  }
}
```

## 영속성 테스트 (DataJpaTest)

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestContainersConfig.class)
class MarketRepositoryTest {
  @Autowired MarketRepository repo;

  @Test
  void savesAndFinds() {
    MarketEntity entity = new MarketEntity();
    entity.setName("Test");
    repo.save(entity);

    Optional<MarketEntity> found = repo.findByName("Test");
    assertThat(found).isPresent();
  }
}
```

## Testcontainers

- 운영과 같도록 Postgres/Redis는 재사용 가능한 컨테이너를 쓴다
- `@ServiceConnection`(권장) 또는 `@DynamicPropertySource`로 JDBC URL을 스프링 컨텍스트에 주입한다

## 커버리지 (JaCoCo)

Maven 예시 (이 프로젝트는 Gradle `jacoco` 플러그인을 쓴다):
```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.14</version>
  <executions>
    <execution>
      <goals><goal>prepare-agent</goal></goals>
    </execution>
    <execution>
      <id>report</id>
      <phase>verify</phase>
      <goals><goal>report</goal></goals>
    </execution>
  </executions>
</plugin>
```

## 단언(Assertion)

- 가독성을 위해 AssertJ(`assertThat`)를 쓴다
- JSON 응답은 `jsonPath`로 검증한다
- 예외는 `assertThatThrownBy(...)`로 검증한다

## 테스트 데이터 빌더

```java
class MarketBuilder {
  private String name = "Test";
  MarketBuilder withName(String name) { this.name = name; return this; }
  Market build() { return new Market(null, name, MarketStatus.ACTIVE); }
}
```

## CI 명령어

- Maven: `mvn -T 4 test` or `mvn verify`
- Gradle: `./gradlew test jacocoTestReport`

**기억할 것**: 테스트는 빠르고, 서로 독립적이고, 결과가 항상 같아야 한다. 구현 세부가 아니라 동작을 테스트한다.
