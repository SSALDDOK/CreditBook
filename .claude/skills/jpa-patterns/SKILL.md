---
name: jpa-patterns
description: Spring Boot에서 JPA/Hibernate 엔티티 설계, 연관관계, 쿼리 최적화, 트랜잭션, 감사(auditing), 인덱싱, 페이지네이션, 커넥션 풀 패턴. JPA 엔티티·연관관계를 설계하거나 Hibernate 쿼리·트랜잭션·N+1 문제를 고칠 때 사용.
metadata:
  origin: ECC
---

# JPA/Hibernate 패턴

Spring Boot에서 데이터 모델링, 리포지토리, 성능 튜닝을 할 때 참고한다.

## 언제 쓰는가

- JPA 엔티티와 테이블 매핑 설계
- 연관관계 정의 (@OneToMany, @ManyToOne, @ManyToMany)
- 쿼리 최적화 (N+1 방지, 페치 전략, 프로젝션)
- 트랜잭션, 감사(auditing), 소프트 삭제 설정
- 페이지네이션, 정렬, 커스텀 리포지토리 메서드 구성
- 커넥션 풀(HikariCP) 또는 2차 캐시 튜닝

## 엔티티 설계

```java
@Entity
@Table(name = "markets", indexes = {
  @Index(name = "idx_markets_slug", columnList = "slug", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
public class MarketEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(nullable = false, unique = true, length = 120)
  private String slug;

  @Enumerated(EnumType.STRING)
  private MarketStatus status = MarketStatus.ACTIVE;

  @CreatedDate private Instant createdAt;
  @LastModifiedDate private Instant updatedAt;
}
```

감사(auditing) 활성화:
```java
@Configuration
@EnableJpaAuditing
class JpaConfig {}
```

## 연관관계와 N+1 방지

```java
@OneToMany(mappedBy = "market", cascade = CascadeType.ALL, orphanRemoval = true)
private List<PositionEntity> positions = new ArrayList<>();
```

- 기본은 지연 로딩(lazy). 필요할 때만 쿼리에서 `JOIN FETCH`를 쓴다
- 컬렉션에 `EAGER`를 쓰지 않는다. 조회 경로는 DTO 프로젝션을 쓴다

```java
@Query("select m from MarketEntity m left join fetch m.positions where m.id = :id")
Optional<MarketEntity> findWithPositions(@Param("id") Long id);
```

## 리포지토리 패턴

```java
public interface MarketRepository extends JpaRepository<MarketEntity, Long> {
  Optional<MarketEntity> findBySlug(String slug);

  @Query("select m from MarketEntity m where m.status = :status")
  Page<MarketEntity> findByStatus(@Param("status") MarketStatus status, Pageable pageable);
}
```

- 가벼운 조회는 프로젝션을 쓴다:
```java
public interface MarketSummary {
  Long getId();
  String getName();
  MarketStatus getStatus();
}
Page<MarketSummary> findAllBy(Pageable pageable);
```

## 트랜잭션

- 서비스 메서드에 `@Transactional`을 붙인다
- 조회 경로는 `@Transactional(readOnly = true)`로 최적화한다
- 전파(propagation) 속성은 신중히 고르고, 오래 걸리는 트랜잭션은 피한다

```java
@Transactional
public Market updateStatus(Long id, MarketStatus status) {
  MarketEntity entity = repo.findById(id)
      .orElseThrow(() -> new EntityNotFoundException("Market"));
  entity.setStatus(status);
  return Market.from(entity);
}
```

## 페이지네이션

```java
PageRequest page = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
Page<MarketEntity> markets = repo.findByStatus(MarketStatus.ACTIVE, page);
```

커서 방식 페이지네이션은 JPQL에 정렬과 함께 `id > :lastId` 조건을 넣는다.

## 인덱싱과 성능

- 자주 쓰는 필터(`status`, `slug`, 외래 키)에 인덱스를 건다
- 쿼리 패턴에 맞는 복합 인덱스를 쓴다 (`status, created_at`)
- `select *`를 피하고 필요한 컬럼만 조회한다
- 대량 쓰기는 `saveAll`과 `hibernate.jdbc.batch_size`로 배치 처리한다

## 커넥션 풀 (HikariCP)

권장 설정:
```
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.validation-timeout=5000
```

PostgreSQL LOB 처리를 위해 추가:
```
spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true
```

## 캐시

- 1차 캐시는 EntityManager 단위다. 트랜잭션을 넘어 엔티티를 들고 있지 않는다
- 조회가 많은 엔티티는 2차 캐시를 신중히 검토하고, 무효화(eviction) 전략을 검증한다

## 마이그레이션

- Flyway 또는 Liquibase를 쓴다. 운영에서 Hibernate 자동 DDL에 의존하지 않는다
- 마이그레이션은 멱등적·추가형으로 유지하고, 계획 없이 컬럼을 삭제하지 않는다

## 데이터 접근 테스트

- 운영과 같은 환경을 위해 `@DataJpaTest` + Testcontainers를 쓴다
- 로그로 SQL 효율을 확인한다: `logging.level.org.hibernate.SQL=DEBUG`, 파라미터 값은 `logging.level.org.hibernate.orm.jdbc.bind=TRACE`

**기억할 것**: 엔티티는 가볍게, 쿼리는 의도적으로, 트랜잭션은 짧게. 페치 전략과 프로젝션으로 N+1을 막고, 읽기·쓰기 경로에 맞춰 인덱스를 건다.
