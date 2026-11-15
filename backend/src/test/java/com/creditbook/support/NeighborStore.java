package com.creditbook.support;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
 * 각 테스트 클래스가 끝나면 {@link NeighborStoreUnchangedExtension} 이 이 매장이 처음 그대로인지 본다 — 어느 테스트든
 * 다른 매장 데이터를 건드리면 그 클래스가 실패한다.
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

	public NeighborStore(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
			EmployeeRepository employeeRepository, PasswordEncoder passwordEncoder, CustomerService customerService,
			ChargeService chargeService, UsageService usageService, CancelService cancelService, Clock clock) {
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
	}

	public UUID storeId() {
		return storeId;
	}

	/** 처음 채운 직후와 지금이 같은지 본다. 다르면 무엇이 바뀌었는지 적어 실패한다. */
	public void assertUnchanged(String testClassName) {
		if (initial == null) {
			return; // 컨텍스트가 다 뜨지 못한 경우 — 그 실패는 테스트 쪽에서 이미 보인다
		}
		Snapshot now = snapshot();
		if (!now.equals(initial)) {
			throw new AssertionError("이웃 매장이 바뀌었다 — " + testClassName + " 가 다른 매장 데이터를 건드렸다. 바뀐 것: "
					+ initial.changedParts(now) + "\n  처음: " + initial + "\n  지금: " + now);
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
