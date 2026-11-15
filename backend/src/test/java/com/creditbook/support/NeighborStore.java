package com.creditbook.support;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.application.RegisteredCustomer;
import com.creditbook.prepaid.application.CancelService;
import com.creditbook.prepaid.application.ChargeService;
import com.creditbook.prepaid.application.UsageService;
import com.creditbook.prepaid.domain.IdempotencyKey;

/**
 * 이웃 매장 (REQ-36, TC-27·TC-52) — 모든 통합 테스트 옆에 늘 있는, 데이터가 찬 다른 매장.
 * <p>
 * 통합 테스트는 대부분 자기 매장 하나만 만들어 그 안에서 검사한다. 이 매장이 같은 DB 에 늘 있으면 기존 테스트가 모두
 * '매장 2개' 조건에서 다시 돈다 — 대사(§6 쿼리는 매장 구분 없이 전체를 본다)·동시성·append-only·권한·멱등키 모두.
 * 두 겹으로 지킨다 ({@link NeighborStoreTestExecutionListener} 가 모든 통합 테스트에 건다).
 * <ul>
 * <li>쓰는 순간 막기 — 채운 직후 이 매장 행(과 그 행을 가리키는 행)에 대한 INSERT·UPDATE·DELETE 를 테스트 DB 트리거가
 * 그 문장에서 실패시킨다. 롤백되는 테스트·실패한 요청·다른 스레드 안의 쓰기도 잡는다. 시도 횟수는 롤백돼도 남는
 * 시퀀스에 세어, 예외를 삼킨 테스트도 메서드가 끝날 때 실패한다.</li>
 * <li>끝나고 비교 — 클래스가 끝나면 표별 지문·거래 건수·잔액 합계가 처음과 같은지 본다(트리거가 없어진 경우의 안전망).</li>
 * </ul>
 * 읽기(다른 매장 데이터가 응답에 보이는 것)는 이 장치가 아니라 매장 간 격리 테스트(TC-60)가 검증한다.
 * <p>
 * 컨텍스트(= 테스트 DB 컨테이너)마다 한 번, 실제 서비스로 채운다. 잔액·거래 후 잔액을 SQL 로 넣지 않고 도메인이 계산하게
 * 하려고 — 그래야 대사 쿼리가 이 매장 거래도 진짜로 검사한다.
 */
public class NeighborStore implements ApplicationListener<ApplicationReadyEvent> {

	// 다른 테스트가 쓰지 않는 값으로 — 아이디는 모든 매장에서 유일, 연락처는 전체 건수를 세는 테스트가 있어 겹치지 않게
	static final String OWNER_LOGIN_ID = "neighbor-owner";

	static final String STAFF_LOGIN_ID = "neighbor-staff";

	static final String INACTIVE_STAFF_LOGIN_ID = "neighbor-left";

	static final String ACTIVE_CUSTOMER_PHONE = "0509990001";

	static final String INACTIVE_CUSTOMER_PHONE = "0509990002";

	private static final Logger log = LoggerFactory.getLogger(NeighborStore.class);

	// 트리거가 시도를 셀 시퀀스 — 시퀀스 값은 트랜잭션이 롤백돼도 되돌아가지 않는다
	private static final String WRITE_ATTEMPTS_SEQUENCE = "test_neighbor_store_write_attempts";

	// 이웃 매장 행을 가리킬 수 있는 칸 — 표마다. 이 칸 값이 이웃 매장 id 중 하나면 그 쓰기를 막는다
	private static final Map<String, List<String>> GUARDED_COLUMNS = Map.of(
			"stores", List.of("id"),
			"employees", List.of("store_id"),
			"customers", List.of("store_id"),
			"prepaid_accounts", List.of("store_id"),
			"ledger_entries", List.of("store_id", "account_id", "performed_by", "reverses_id"),
			"phone_access_logs", List.of("customer_id", "accessed_by"),
			"revoked_tokens", List.of("employee_id"));

	private final DataSource dataSource;

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate transactionTemplate;

	private final EmployeeRepository employeeRepository;

	private final PasswordEncoder passwordEncoder;

	private final CustomerService customerService;

	private final ChargeService chargeService;

	private final UsageService usageService;

	private final CancelService cancelService;

	private final Clock clock;

	private UUID storeId;

	private Snapshot initial;

	private long seenWriteAttempts;

	public NeighborStore(DataSource dataSource, JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
			EmployeeRepository employeeRepository, PasswordEncoder passwordEncoder, CustomerService customerService,
			ChargeService chargeService, UsageService usageService, CancelService cancelService, Clock clock) {
		this.dataSource = dataSource;
		this.jdbcTemplate = jdbcTemplate;
		this.transactionTemplate = transactionTemplate;
		this.employeeRepository = employeeRepository;
		this.passwordEncoder = passwordEncoder;
		this.customerService = customerService;
		this.chargeService = chargeService;
		this.usageService = usageService;
		this.cancelService = cancelService;
		this.clock = clock;
	}

	@Override
	public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
		// 같은 컨텍스트에서 두 번 오면 한 번만
		if (storeId != null) {
			return;
		}
		storeId = fill();
		initial = snapshot();
		installWriteGuard();
	}

	public UUID storeId() {
		return storeId;
	}

	/**
	 * 이웃 매장에 쓰려는 시도가 지난 확인 뒤로 있었는지 본다. 테스트 트랜잭션과 따로 새 연결로 읽는다 — 트리거 예외로 테스트
	 * 트랜잭션이 중단된 상태여도 읽히게.
	 */
	public synchronized void assertNoWriteAttempts(String where) {
		requireFilled();
		long now = readWriteAttempts();
		if (now != seenWriteAttempts) {
			long attempts = now - seenWriteAttempts;
			seenWriteAttempts = now; // 다음 테스트가 같은 시도로 또 실패하지 않게
			throw new AssertionError("이웃 매장에 쓰려는 시도 " + attempts + "건 — " + where
					+ " 가 다른 매장 데이터를 바꾸려 했다. 어느 표·칸인지는 로그의 NEIGHBOR_STORE_WRITE 를 본다");
		}
	}

	/** 처음 채운 직후와 지금이 같은지 본다. 다르면 무엇이 바뀌었는지 적어 실패한다. */
	public synchronized void assertUnchanged(String testClassName) {
		requireFilled();
		Snapshot now = snapshot();
		if (!now.equals(initial)) {
			Snapshot before = initial;
			initial = now; // 같은 컨텍스트를 쓰는 다음 클래스가 이 변경으로 또 실패하지 않게
			throw new AssertionError("이웃 매장이 바뀌었다 — " + testClassName + " 가 다른 매장 데이터를 건드렸다. 바뀐 것: "
					+ before.changedParts(now) + "\n  처음: " + before + "\n  지금: " + now);
		}
		log.info("neighbor store unchanged after {}", testClassName);
	}

	private void requireFilled() {
		if (initial == null) {
			// 컨텍스트는 떴는데 채우기가 안 됐다 — 그냥 지나가면 모든 테스트가 매장 1개 조건으로 돈다
			throw new AssertionError("이웃 매장이 채워지지 않았다");
		}
	}

	private UUID fill() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO stores (id, name) VALUES (?, ?)", id, "이웃매장");
		Instant now = clock.instant();
		// 이웃 매장 계정으로는 로그인하지 않는다 - 비밀번호는 실행마다 무작위(리포에 비밀번호 문자열을 두지 않음)
		String hash = passwordEncoder.encode(UUID.randomUUID().toString());

		// 직원 3명: 사장·직원·그만둔 직원(비활성)
		Employee owner = transactionTemplate.execute(status -> {
			Employee e = TestEmployees.owner(id, OWNER_LOGIN_ID, hash, "이웃사장", now);
			employeeRepository.add(e);
			return e;
		});
		Employee staff = transactionTemplate.execute(status -> {
			Employee e = TestEmployees.readyStaff(id, STAFF_LOGIN_ID, hash, "이웃직원", now);
			employeeRepository.add(e);
			return e;
		});
		transactionTemplate.executeWithoutResult(status -> {
			Employee e = TestEmployees.readyStaff(id, INACTIVE_STAFF_LOGIN_ID, hash, "그만둔직원", now);
			employeeRepository.add(e);
			e.deactivate(now);
		});

		// 활성 고객: 거래 4종이 모두 있게 — 충전 50,000 → 사용 12,000 → 충전 10,000 취소 → 사용 3,000 취소 = 잔액 38,000
		RegisteredCustomer active = customerService.register(id, "이웃고객", ACTIVE_CUSTOMER_PHONE);
		chargeService.charge(id, active.customerId(), won(50_000), "현금", staff.getId(), newKey());
		usageService.use(id, active.customerId(), won(12_000), "음료", staff.getId(), newKey());
		UUID chargeToCancel = chargeService
				.charge(id, active.customerId(), won(10_000), "카드", staff.getId(), newKey()).entryId();
		cancelService.cancelCharge(id, active.customerId(), chargeToCancel, "잘못 충전", staff.getId(), newKey());
		UUID useToCancel = usageService
				.use(id, active.customerId(), won(3_000), "디저트", staff.getId(), newKey()).entryId();
		cancelService.cancelUse(id, active.customerId(), useToCancel, "잘못 사용", staff.getId(), newKey());

		// 비활성 고객: 다 쓰고 0원이 된 뒤 사장이 비활성화
		RegisteredCustomer inactive = customerService.register(id, "이웃옛고객", INACTIVE_CUSTOMER_PHONE);
		chargeService.charge(id, inactive.customerId(), won(5_000), "현금", staff.getId(), newKey());
		usageService.use(id, inactive.customerId(), won(5_000), "원두", staff.getId(), newKey());
		customerService.deactivate(id, inactive.customerId(), owner.getId());

		return id;
	}

	// 테스트 DB 에만 거는 트리거 — 운영 마이그레이션이 아니다. 이웃 매장 id(매장·직원·고객·계좌·거래)를 함수에 박아 두고,
	// 표마다 GUARDED_COLUMNS 칸의 옛 값·새 값이 그중 하나면 시퀀스를 올리고 그 문장을 실패시킨다.
	private void installWriteGuard() {
		List<UUID> ids = new ArrayList<>();
		ids.add(storeId);
		for (String table : List.of("employees", "customers", "prepaid_accounts", "ledger_entries")) {
			ids.addAll(jdbcTemplate.queryForList("SELECT id FROM " + table + " WHERE store_id = ?", UUID.class, storeId));
		}
		String idArray = ids.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElseThrow();

		// 칸 이름이 틀리면 트리거는 그 칸을 NULL 로 읽어 조용히 감시를 건너뛴다 — 걸기 전에 칸이 실제로 있는지 본다
		GUARDED_COLUMNS.forEach((table, columns) -> columns.forEach(column -> {
			Long found = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()"
							+ " AND table_name = ? AND column_name = ?",
					Long.class, table, column);
			if (found == null || found != 1) {
				throw new IllegalStateException("이웃 매장 감시 칸이 없다: " + table + "." + column);
			}
		}));

		jdbcTemplate.execute("CREATE SEQUENCE " + WRITE_ATTEMPTS_SEQUENCE);
		jdbcTemplate.execute("""
				CREATE FUNCTION fn_test_neighbor_store_guard() RETURNS trigger LANGUAGE plpgsql AS $$
				DECLARE
				    v_ids UUID[] := '{%s}';
				    v_col TEXT;
				    v_old JSONB;
				    v_new JSONB;
				BEGIN
				    IF TG_OP <> 'INSERT' THEN v_old := to_jsonb(OLD); END IF;
				    IF TG_OP <> 'DELETE' THEN v_new := to_jsonb(NEW); END IF;
				    FOREACH v_col IN ARRAY TG_ARGV LOOP
				        IF (v_old ->> v_col)::uuid = ANY (v_ids) OR (v_new ->> v_col)::uuid = ANY (v_ids) THEN
				            PERFORM nextval('%s');
				            RAISE EXCEPTION 'NEIGHBOR_STORE_WRITE: %% on %% (%%)', TG_OP, TG_TABLE_NAME, v_col;
				        END IF;
				    END LOOP;
				    RETURN COALESCE(NEW, OLD);
				END
				$$
				""".formatted(idArray, WRITE_ATTEMPTS_SEQUENCE));
		GUARDED_COLUMNS.forEach((table, columns) -> jdbcTemplate.execute("CREATE TRIGGER trg_test_neighbor_store_guard"
				+ " BEFORE INSERT OR UPDATE OR DELETE ON " + table + " FOR EACH ROW EXECUTE FUNCTION"
				+ " fn_test_neighbor_store_guard(" + String.join(", ", columns.stream().map(c -> "'" + c + "'").toList())
				+ ")"));
	}

	private long readWriteAttempts() {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM "
						+ WRITE_ATTEMPTS_SEQUENCE)) {
			rs.next();
			return rs.getLong(1);
		}
		catch (SQLException e) {
			throw new IllegalStateException("이웃 매장 쓰기 시도 횟수를 읽지 못했다", e);
		}
	}

	// 행 전체를 글자로 바꿔 지문을 만든다 — 어느 칸이 바뀌어도(비밀번호 초기화·잠금·로그인 시각 포함) 다르게 나온다
	private Snapshot snapshot() {
		return new Snapshot(
				fingerprint("SELECT md5(string_agg(t::text, ',' ORDER BY t.id)) FROM stores t WHERE t.id = ?"),
				fingerprint("SELECT md5(string_agg(t::text, ',' ORDER BY t.id)) FROM employees t WHERE t.store_id = ?"),
				fingerprint("SELECT md5(string_agg(t::text, ',' ORDER BY t.id)) FROM customers t WHERE t.store_id = ?"),
				fingerprint(
						"SELECT md5(string_agg(t::text, ',' ORDER BY t.id)) FROM prepaid_accounts t WHERE t.store_id = ?"),
				jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries WHERE store_id = ?", Long.class,
						storeId),
				jdbcTemplate.queryForObject(
						"SELECT coalesce(sum(balance), 0) FROM prepaid_accounts WHERE store_id = ?", BigDecimal.class,
						storeId),
				fingerprint(
						"SELECT md5(string_agg(t::text, ',' ORDER BY t.seq)) FROM ledger_entries t WHERE t.store_id = ?"));
	}

	private String fingerprint(String sql) {
		return jdbcTemplate.queryForObject(sql, String.class, storeId);
	}

	private static BigDecimal won(long amount) {
		return BigDecimal.valueOf(amount);
	}

	private static IdempotencyKey newKey() {
		return IdempotencyKey.of("neighbor-" + UUID.randomUUID());
	}

	/** 이웃 매장의 상태. 잔액 합계는 BigDecimal 이라 scale 이 같아야 equals 가 맞는다 — 같은 쿼리로만 만들므로 같다. */
	record Snapshot(String store, String employees, String customers, String accounts, long entryCount,
			BigDecimal balanceSum, String entries) {

		// 실패 메시지용 — 어느 표가 바뀌었는지 이름만
		List<String> changedParts(Snapshot other) {
			List<String> parts = new ArrayList<>();
			if (!Objects.equals(store, other.store)) {
				parts.add("stores");
			}
			if (!Objects.equals(employees, other.employees)) {
				parts.add("employees");
			}
			if (!Objects.equals(customers, other.customers)) {
				parts.add("customers");
			}
			if (!Objects.equals(accounts, other.accounts) || !Objects.equals(balanceSum, other.balanceSum)) {
				parts.add("prepaid_accounts");
			}
			if (entryCount != other.entryCount || !Objects.equals(entries, other.entries)) {
				parts.add("ledger_entries");
			}
			return parts;
		}

	}

}
