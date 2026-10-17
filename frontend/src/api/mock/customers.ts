import { ApiError, type FieldError } from '../client'
import type {
  CustomerDetailResponse,
  CustomerListResponse,
  CustomerRegisterRequest,
  CustomerRegisterResponse,
  CustomerResponse,
  CustomerSearchParams,
  RecentLedgerEntryResponse,
} from '../customers'
import type { TransactionResponse } from '../transactions'

// 화면 개발용 목 데이터. 이름은 가상이고, 연락처는 서버 응답과 같은 "이미 가린" 형태로만 둔다.
// 잔액·변동 후 잔액(balanceAfter)·부호 붙은 금액(signedAmount)·취소 가능 여부(cancellable)는 서버가 준 값처럼
// 고정 값으로 적어 둔다(화면도 목도 금액을 계산하지 않는다). 장부의 잔액 흐름은 손으로 맞춰 적었다.
//
// 고객마다 장부 전체(entries, 최신순)를 하나 두고, 고객 상세의 최근 거래(recentEntries)는 그 앞 5건,
// 거래 이력(mock/transactions.ts)은 그 전체를 페이지로 나눠 돌려준다 — 두 화면이 같은 장부를 본다.
//
// 상세·거래 이력 화면 확인용 고객 (화면설계 SCR-5·SCR-8 이미지 기준)
// - 박서연  …001  활성, 잔액 95,500원, 거래 10건(SCR-8 이미지 그대로 — 충전 취소·사용 취소와 '취소됨' 원거래 포함)
// - 이준호  …002  활성, 잔액 0원 → [사용] 비활성 + "잔액이 없습니다". 10-15 충전 30,000원 취소는 잔액 부족으로 거절(409)
// - 정하은  …005  활성, 잔액 128,000원, 거래 28건 → 거래 이력 페이지 나눔 확인용
// - 오지훈  …051  비활성, 잔액 0원 → 안내 배너·'비활성' 배지 (목록에서는 '비활성 포함'을 켜야 보인다)
// - 한지민  …052  활성, 잔액 0원, 거래 0건 → "마지막 거래" 줄 숨김, 최근 거래·거래 이력 빈 안내
// 그 밖의 고객은 거래 없이(entries: []) 돌려준다.
//
// 고객 등록(SCR-4) 같은 번호 확인용 — 응답에는 가린 번호만 나가므로, 가짜 서버만 아는 원래 번호를 MOCK_PHONE_DIGITS 에 둔다.
// - 010-3381-4821  박서연(활성)                     → "같은 번호의 고객이 있습니다"
// - 010-5521-3390  오지훈(비활성)                   → "같은 번호의 비활성 고객이 있습니다" + [해당 고객으로 이동]
// - 010-2470-1093  이준호(활성) + 서지우(비활성)     → 활성·비활성이 섞인 경우("같은 번호의 고객이 있습니다")
// - 010-9012-7781  윤하린·윤하늘(둘 다 비활성)       → 비활성 여럿: 행을 눌러 그 고객으로 이동
// 목에서 새로 등록한 고객의 번호도 여기에 더해 다시 등록하면 같은 번호로 잡힌다(새로고침하면 사라진다).
// 충전·사용·취소 목(mock/charges.ts·uses.ts·cancels.ts)이 성공하면 recordMockCharge·recordMockUse·recordMockCancel 이 이 장부를 바꾼다
// — 다시 불러오면 바뀐 잔액·거래가 상세와 거래 이력 모두에 보인다.
// 형식이 UUID 가 아닌 id 는 400 INVALID_INPUT, 없는 UUID 는 404 CUSTOMER_NOT_FOUND.

type MockCustomer = CustomerResponse & { entries: TransactionResponse[] }

function mockId(n: number): string {
  return `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
}

function entryId(n: number): string {
  return `00000000-0000-4000-a000-${String(n).padStart(12, '0')}`
}

type EntryRow = [
  n: number,
  type: TransactionResponse['type'],
  amount: number,
  signedAmount: number,
  balanceAfter: number,
  memo: string | null,
  performedAt: string,
  performedByName: string,
  flags?: { cancelled?: boolean; reversesId?: number; cancellable?: boolean },
]

/** 표로 적은 거래 한 줄을 응답 모양으로 옮긴다(값은 적힌 그대로 — 계산 없음) */
function toEntry([n, type, amount, signedAmount, balanceAfter, memo, performedAt, performedByName, flags = {}]: EntryRow): TransactionResponse {
  return {
    id: entryId(n),
    type,
    amount,
    signedAmount,
    balanceAfter,
    memo,
    performedAt,
    performedByName,
    cancelled: flags.cancelled ?? false,
    reversesId: flags.reversesId === undefined ? null : entryId(flags.reversesId),
    cancellable: flags.cancellable ?? false,
  }
}

// 박서연: 화면설계 SCR-8 이미지의 장부 10건(최신순). 충전 → 충전 취소, 사용 → 사용 취소 쌍이 모두 들어 있다.
// 취소된 원거래는 cancelled: true, 반제 행의 memo 는 취소 사유다.
// cancellable 은 이미지의 [취소] 버튼 상태 그대로다(이미지 기준일 10-15 — 그날 충전과 취소되지 않은 사용만 true).
const PARK_ROWS: EntryRow[] = [
  [106, 'CHARGE', 50000, 50000, 95500, '카드', '2026-10-15T06:40:00Z', '김직원', { cancellable: true }],
  [105, 'USE', 4500, -4500, 45500, '아메리카노 2잔', '2026-10-15T05:22:00Z', '김직원', { cancellable: true }],
  [104, 'USE_CANCEL', 6000, 6000, 50000, '손님이 주문 취소', '2026-10-15T04:12:00Z', '김직원', { reversesId: 103 }],
  [103, 'USE', 6000, -6000, 44000, '카페라떼, 쿠키', '2026-10-15T04:05:00Z', '김직원', { cancelled: true }],
  [102, 'CHARGE_CANCEL', 30000, -30000, 50000, '금액 잘못 입력', '2026-09-18T00:20:00Z', '박사장', { reversesId: 101 }],
  [101, 'CHARGE', 30000, 30000, 80000, '계좌이체', '2026-09-18T00:16:00Z', '김직원', { cancelled: true }],
  [100, 'USE', 5000, -5000, 50000, '아메리카노, 스콘', '2026-09-12T08:02:00Z', '김직원', { cancellable: true }],
  [99, 'CHARGE', 10000, 10000, 55000, '계좌이체', '2026-09-10T00:03:00Z', '박사장'],
  [98, 'USE', 5000, -5000, 45000, '라떼 2잔', '2026-09-05T06:10:00Z', '김직원', { cancellable: true }],
  [97, 'CHARGE', 50000, 50000, 50000, '현금', '2026-09-01T01:30:00Z', '박사장'],
]

// 이준호: 잔액 0원. 10-15 10:05 의 30,000원 충전(당일 — 취소 가능)은 이미 다 써서, 충전 취소를 누르면
// 409 CHARGE_ALREADY_USED(현재 잔액 0원 · 충전 30,000원)가 난다 — 화면설계 충전 취소 거절 이미지 그대로
const LEE_ROWS: EntryRow[] = [
  [205, 'USE', 9000, -9000, 0, '아메리카노 2잔', '2026-10-15T05:22:00Z', '김직원', { cancellable: true }],
  [207, 'USE', 30000, -30000, 9000, '원두 1kg, 디저트 세트', '2026-10-15T03:30:00Z', '김직원', { cancellable: true }],
  [206, 'CHARGE', 30000, 30000, 39000, '현금', '2026-10-15T01:05:00Z', '김직원', { cancellable: true }],
  [204, 'USE', 6500, -6500, 9000, '샌드위치 1개', '2026-10-04T03:15:00Z', '김직원', { cancellable: true }],
  [203, 'CHARGE', 10000, 10000, 15500, '카드', '2026-10-02T02:30:00Z', '박사장'],
  [202, 'USE', 4500, -4500, 5500, '라떼 1잔', '2026-09-24T23:50:00Z', '김직원', { cancellable: true }],
  [201, 'CHARGE', 10000, 10000, 10000, '현금', '2026-09-20T00:02:00Z', '박사장'],
]

const OH_ROWS: EntryRow[] = [
  [305, 'USE', 4500, -4500, 0, '아메리카노 1잔', '2026-08-30T07:10:00Z', '김직원', { cancellable: true }],
  [304, 'USE', 10300, -10300, 4500, '라떼 2잔', '2026-08-21T04:45:00Z', '김직원', { cancellable: true }],
  [303, 'USE', 4800, -4800, 14800, '케이크 1조각', '2026-08-14T06:00:00Z', '김직원', { cancellable: true }],
  [302, 'USE', 10400, -10400, 19600, '라떼 2잔', '2026-08-10T04:45:00Z', '김직원', { cancellable: true }],
  [301, 'CHARGE', 30000, 30000, 30000, '현금', '2026-08-05T01:20:00Z', '박사장'],
]

// 정하은: 거래 이력 페이지 나눔 확인용 28건(최신순). 잔액 흐름은 손으로 맞춰 적었다(최종 128,000원)
const JUNG_ROWS: EntryRow[] = [
  [428, 'USE', 5000, -5000, 128000, '아메리카노, 쿠키', '2026-10-13T05:40:00Z', '김직원', { cancellable: true }],
  [427, 'USE', 7000, -7000, 133000, '라떼 2잔', '2026-10-06T03:10:00Z', '김직원', { cancellable: true }],
  [426, 'CHARGE', 20000, 20000, 140000, '현금', '2026-10-01T01:00:00Z', '박사장'],
  [425, 'USE', 4000, -4000, 120000, '아메리카노 1잔', '2026-09-26T06:20:00Z', '김직원', { cancellable: true }],
  [424, 'USE', 6000, -6000, 124000, '카페라떼, 쿠키', '2026-09-23T04:15:00Z', '김직원', { cancellable: true }],
  [423, 'USE', 5500, -5500, 130000, '베이커리', '2026-09-21T07:30:00Z', '김직원', { cancellable: true }],
  [422, 'USE', 4500, -4500, 135500, '아메리카노 1잔', '2026-09-19T02:45:00Z', '김직원', { cancellable: true }],
  [421, 'CHARGE', 50000, 50000, 140000, '계좌이체', '2026-09-16T00:30:00Z', '박사장'],
  [420, 'USE', 5500, -5500, 90000, '원두 200g', '2026-09-14T05:05:00Z', '김직원', { cancellable: true }],
  [419, 'USE', 4500, -4500, 95500, '아메리카노 1잔', '2026-09-12T03:40:00Z', '김직원', { cancellable: true }],
  [418, 'USE', 4000, -4000, 100000, '디저트', '2026-09-09T06:50:00Z', '김직원', { cancellable: true }],
  [417, 'USE', 6000, -6000, 104000, '라떼, 스콘', '2026-09-07T04:25:00Z', '김직원', { cancellable: true }],
  [416, 'USE', 5500, -5500, 110000, '베이커리', '2026-09-05T08:10:00Z', '김직원', { cancellable: true }],
  [415, 'USE', 4500, -4500, 115500, '아메리카노 1잔', '2026-09-02T02:55:00Z', '김직원', { cancellable: true }],
  [414, 'CHARGE', 30000, 30000, 120000, '카드', '2026-08-31T01:15:00Z', '박사장'],
  [413, 'USE', 5700, -5700, 90000, '음료, 베이커리', '2026-08-29T05:35:00Z', '김직원', { cancellable: true }],
  [412, 'USE', 4800, -4800, 95700, '케이크 1조각', '2026-08-26T06:05:00Z', '김직원', { cancellable: true }],
  [411, 'USE', 4500, -4500, 100500, '아메리카노 1잔', '2026-08-24T03:20:00Z', '김직원', { cancellable: true }],
  [410, 'USE', 5000, -5000, 105000, '아메리카노, 쿠키', '2026-08-22T07:45:00Z', '김직원', { cancellable: true }],
  [409, 'USE', 6500, -6500, 110000, '샌드위치 1개', '2026-08-19T04:00:00Z', '김직원', { cancellable: true }],
  [408, 'USE', 4500, -4500, 116500, '아메리카노 1잔', '2026-08-17T02:30:00Z', '김직원', { cancellable: true }],
  [407, 'USE', 9000, -9000, 121000, '라떼 2잔, 쿠키', '2026-08-15T05:50:00Z', '김직원', { cancellable: true }],
  [406, 'CHARGE', 50000, 50000, 130000, '계좌이체', '2026-08-12T00:40:00Z', '박사장'],
  [405, 'USE', 4500, -4500, 80000, '아메리카노 1잔', '2026-08-10T06:35:00Z', '김직원', { cancellable: true }],
  [404, 'USE', 5000, -5000, 84500, 'MD', '2026-08-08T04:10:00Z', '김직원', { cancellable: true }],
  [403, 'USE', 6000, -6000, 89500, '카페라떼, 쿠키', '2026-08-05T07:25:00Z', '김직원', { cancellable: true }],
  [402, 'USE', 4500, -4500, 95500, '아메리카노 1잔', '2026-08-03T03:00:00Z', '김직원', { cancellable: true }],
  [401, 'CHARGE', 100000, 100000, 100000, '현금', '2026-08-01T01:10:00Z', '박사장'],
]

const FIRST: Array<Omit<MockCustomer, 'id'>> = [
  { name: '박서연', maskedPhone: '010-****-4821', balance: 95500, createdAt: '2026-10-05T02:10:00Z', active: true, entries: PARK_ROWS.map(toEntry) },
  { name: '이준호', maskedPhone: '010-****-1093', balance: 0, createdAt: '2026-10-02T05:30:00Z', active: true, entries: LEE_ROWS.map(toEntry) },
  { name: '최다은', maskedPhone: '010-****-7740', balance: 12200, createdAt: '2026-09-30T01:00:00Z', active: true, entries: [] },
  { name: '강민석', maskedPhone: '010-****-2256', balance: 0, createdAt: '2026-09-26T07:45:00Z', active: true, entries: [] },
  { name: '정하은', maskedPhone: '010-****-6612', balance: 128000, createdAt: '2026-09-19T03:20:00Z', active: true, entries: JUNG_ROWS.map(toEntry) },
]

// 목록 어디에 끼어도 되도록 id 를 따로 둔다(등록일 순으로 다시 정렬한다)
const EXTRA: MockCustomer[] = [
  { id: mockId(51), name: '오지훈', maskedPhone: '010-****-3390', balance: 0, createdAt: '2026-08-01T02:00:00Z', active: false, entries: OH_ROWS.map(toEntry) },
  { id: mockId(52), name: '한지민', maskedPhone: '010-****-5047', balance: 0, createdAt: '2026-10-15T00:30:00Z', active: true, entries: [] },
  // 고객 등록(SCR-4)의 같은 번호 확인 창 확인용 비활성 고객 — 아래 MOCK_PHONE_DIGITS 참고
  { id: mockId(53), name: '서지우', maskedPhone: '010-****-1093', balance: 0, createdAt: '2026-07-20T02:00:00Z', active: false, entries: [] },
  { id: mockId(54), name: '윤하린', maskedPhone: '010-****-7781', balance: 0, createdAt: '2026-07-10T02:00:00Z', active: false, entries: [] },
  { id: mockId(55), name: '윤하늘', maskedPhone: '010-****-7781', balance: 0, createdAt: '2026-06-02T02:00:00Z', active: false, entries: [] },
]

const FAMILY = ['김', '이', '박', '최', '정', '강', '조', '윤', '장', '임', '한', '오', '서', '신', '권']
const GIVEN = ['지우', '서준', '하린', '도윤', '수아', '예준', '지민', '시우', '유나', '민준', '채원', '현우', '소율']
const BALANCES = [0, 3000, 8500, 15000, 22000, 30000, 47500, 60000, 99000, 150000, 210000, 300000, 412300]
const SUFFIXES = [
  '3398', '5107', '8823', '0412', '2968', '7731', '1185', '6604', '9257', '4479', '3016', '5590', '2841', '7368',
  '0925', '6153', '8410', '1732', '4086', '9671', '3548', '5214', '7902', '2367', '6839', '0158', '8745', '1496',
  '4623', '9380', '3071', '5862', '7194', '2509', '6247', '0836', '8371', '1950', '4718', '9063', '3625',
]

const DAY_MS = 24 * 60 * 60 * 1000
const OLDEST_FIRST_ROW = Date.parse(FIRST[FIRST.length - 1].createdAt)

const MOCK_CUSTOMERS: MockCustomer[] = [
  ...FIRST.map((c, i) => ({ id: mockId(i + 1), ...c })),
  ...SUFFIXES.map((suffix, i) => ({
    id: mockId(FIRST.length + i + 1),
    name: FAMILY[i % FAMILY.length] + GIVEN[(i * 5) % GIVEN.length],
    maskedPhone: `010-****-${suffix}`,
    balance: BALANCES[(i * 7) % BALANCES.length],
    createdAt: new Date(OLDEST_FIRST_ROW - (i + 1) * 2 * DAY_MS).toISOString(),
    active: true,
    entries: [],
  })),
  ...EXTRA,
].sort((a, b) => b.createdAt.localeCompare(a.createdAt))

const PHONE_QUERY = /^[\d\s-]+$/
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const MOCK_DELAY_MS = 250
const RECENT_ENTRY_COUNT = 5

function filter(q: string | undefined, includeInactive: boolean): MockCustomer[] {
  const visible = includeInactive ? MOCK_CUSTOMERS : MOCK_CUSTOMERS.filter((c) => c.active)
  const trimmed = q?.trim() ?? ''
  if (!trimmed) return visible
  // 서버와 같은 규칙: 숫자(하이픈·공백 허용)만이면 연락처 뒷자리, 그 밖은 이름 일부
  if (PHONE_QUERY.test(trimmed)) {
    const digits = trimmed.replace(/\D/g, '')
    return visible.filter((c) => c.maskedPhone.replace(/\D/g, '').endsWith(digits))
  }
  return visible.filter((c) => c.name.includes(trimmed))
}

function toListItem({ entries: _entries, ...item }: MockCustomer): CustomerResponse {
  return item
}

/** 고객 상세의 최근 거래 한 줄 — 거래 이력 응답에서 상세 응답에 없는 필드(reversesId·cancellable)를 뺀다 */
function toRecentEntry({ reversesId: _reversesId, cancellable: _cancellable, ...rest }: TransactionResponse): RecentLedgerEntryResponse {
  return rest
}

/** 목 응답을 잠깐 늦게 돌려준다(로딩 상태 확인용). signal 로 취소되면 AbortError */
export function delayed<T>(produce: () => T, signal?: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      try {
        resolve(produce())
      } catch (e: unknown) {
        reject(e)
      }
    }, MOCK_DELAY_MS)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('Aborted', 'AbortError'))
    })
  })
}

export function searchMockCustomers(
  { q, page = 0, size = 20, includeInactive = false }: CustomerSearchParams,
  signal?: AbortSignal,
): Promise<CustomerListResponse> {
  return delayed(() => {
    const matched = filter(q, includeInactive)
    const start = page * size
    return {
      content: matched.slice(start, start + size).map(toListItem),
      page,
      size,
      totalElements: matched.length,
      totalPages: Math.ceil(matched.length / size),
      hasNext: start + size < matched.length,
    }
  }, signal)
}

/** 경로의 고객 id 를 서버처럼 확인한다 — UUID 형식이 아니면 400, 없으면 404 */
function findCustomer(customerId: string): MockCustomer {
  if (!UUID_PATTERN.test(customerId)) {
    throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.')
  }
  const found = MOCK_CUSTOMERS.find((c) => c.id === customerId)
  if (!found) {
    throw new ApiError(404, 'CUSTOMER_NOT_FOUND', '고객을 찾을 수 없습니다.')
  }
  return found
}

export function getMockCustomer(customerId: string, signal?: AbortSignal): Promise<CustomerDetailResponse> {
  return delayed(() => {
    const { entries, ...customer } = findCustomer(customerId)
    return { ...customer, recentEntries: entries.slice(0, RECENT_ENTRY_COUNT).map(toRecentEntry) }
  }, signal)
}

/** 거래 이력 목(mock/transactions.ts)용 — 고객의 장부 전체(최신순). 400·404 는 상세와 같다 */
export function mockEntriesOf(customerId: string): TransactionResponse[] {
  return findCustomer(customerId).entries
}

type MockLedgerResult = { entryId: string; balanceAfter: number; performedAt: string }

function findForLedger(customerId: string): MockCustomer {
  const found = MOCK_CUSTOMERS.find((c) => c.id === customerId)
  if (!found) {
    throw new ApiError(404, 'CUSTOMER_NOT_FOUND', '고객을 찾을 수 없습니다.')
  }
  return found
}

function appendEntry(
  found: MockCustomer,
  type: 'CHARGE' | 'USE',
  amount: number,
  signedAmount: number,
  serverBalanceAfter: number,
  memo: string | null,
  performedByName: string,
): MockLedgerResult {
  const entry: TransactionResponse = {
    id: crypto.randomUUID(),
    type,
    amount,
    signedAmount,
    balanceAfter: serverBalanceAfter,
    memo,
    performedAt: new Date().toISOString(),
    performedByName,
    cancelled: false,
    reversesId: null,
    // 방금 만든 충전(당일)·사용은 서버가 취소 가능으로 준다고 본다
    cancellable: true,
  }
  found.balance = serverBalanceAfter
  found.entries = [entry, ...found.entries]
  return { entryId: entry.id, balanceAfter: serverBalanceAfter, performedAt: entry.performedAt }
}

/**
 * 충전 목이 성공했을 때 "서버 쪽" 장부를 흉내 낸다 — 잔액을 바꾸고, 장부 맨 앞에 CHARGE 를 넣고,
 * 비활성 고객은 다시 활성화한다(REQ-33).
 * 여기는 가짜 서버(PrepaidAccount 역할)라서 잔액을 더한다. 화면 코드는 이 값을 계산하지 않고 응답·재조회 값만 쓴다.
 */
export function recordMockCharge(customerId: string, amount: number, memo: string | null): MockLedgerResult {
  const found = findForLedger(customerId)
  found.active = true
  return appendEntry(found, 'CHARGE', amount, amount, found.balance + amount, memo, '김직원')
}

/**
 * 사용 목의 "서버 쪽" 장부 — 비활성 고객은 409 CUSTOMER_INACTIVE, 잔액보다 크면 400 INSUFFICIENT_BALANCE(details 에
 * 현재 잔액·부족액), 아니면 잔액을 빼고 장부 맨 앞에 USE 를 넣는다. 잔액과 같은 금액은 받는다(잔액 0원이 된다).
 * 여기는 가짜 서버(PrepaidAccount 역할)라서 잔액을 뺀다. 화면 코드는 이 값을 계산하지 않고 응답·재조회 값만 쓴다.
 */
export function recordMockUse(
  customerId: string,
  amount: number,
  memo: string | null,
  performedByName = '김직원',
): MockLedgerResult {
  const found = findForLedger(customerId)
  if (!found.active) {
    throw new ApiError(409, 'CUSTOMER_INACTIVE', '비활성 고객입니다. 충전하면 다시 활성화됩니다.')
  }
  if (amount > found.balance) {
    throw new ApiError(400, 'INSUFFICIENT_BALANCE', '잔액이 부족합니다.', [], {
      balance: found.balance,
      shortage: amount - found.balance,
    })
  }
  return appendEntry(found, 'USE', amount, -amount, found.balance - amount, memo, performedByName)
}

export type MockCancelKind = 'use' | 'charge'

const CANCEL_TYPES = {
  use: { target: 'USE', reversal: 'USE_CANCEL', alreadyMessage: '이미 취소된 사용 건입니다.' },
  charge: { target: 'CHARGE', reversal: 'CHARGE_CANCEL', alreadyMessage: '이미 취소된 충전 건입니다.' },
} as const

/**
 * 취소 목의 "서버 쪽" 장부 — 서버(PrepaidAccount)와 같은 순서로 판단한다:
 * 거래 없음 404 LEDGER_ENTRY_NOT_FOUND → 반제 행·유형 불일치 400 INVALID_CANCEL_TARGET → 이미 취소됨 409 ALREADY_CANCELLED
 * (details.cancelEntryId) → (충전) 당일 아님 409 CHARGE_CANCEL_NOT_SAME_DAY → (충전) 잔액이 모자람 409 CHARGE_ALREADY_USED
 * (details.balance·chargeAmount). 통과하면 장부 맨 앞에 반제 행(memo = 사유)을 넣고, 원거래를 cancelled 로 바꾼다.
 * 목 장부의 날짜는 고정 값이라 "당일" 은 장부에 적힌 원거래의 cancellable 값으로 대신한다.
 * 사용 취소는 서버처럼 비활성 고객을 다시 활성화한다.
 * 여기는 가짜 서버라서 잔액을 더하고 뺀다. 화면 코드는 이 값을 계산하지 않고 응답·재조회 값만 쓴다.
 */
export function recordMockCancel(
  customerId: string,
  targetEntryId: string,
  kind: MockCancelKind,
  reason: string,
  performedByName = '김직원',
): TransactionResponse {
  const found = findForLedger(customerId)
  const target = found.entries.find((e) => e.id === targetEntryId)
  if (!target) {
    throw new ApiError(404, 'LEDGER_ENTRY_NOT_FOUND', '거래를 찾을 수 없습니다.')
  }
  const types = CANCEL_TYPES[kind]
  if (target.reversesId !== null) {
    throw new ApiError(400, 'INVALID_CANCEL_TARGET', '취소 거래는 다시 취소할 수 없습니다.')
  }
  if (target.type !== types.target) {
    throw new ApiError(400, 'INVALID_CANCEL_TARGET', `${types.reversal} 는 ${types.target} 거래만 취소할 수 있습니다.`)
  }
  if (target.cancelled) {
    const cancel = found.entries.find((e) => e.reversesId === target.id)
    throw new ApiError(409, 'ALREADY_CANCELLED', types.alreadyMessage, [], { cancelEntryId: cancel?.id ?? null })
  }
  if (kind === 'charge') {
    if (!target.cancellable) {
      throw new ApiError(409, 'CHARGE_CANCEL_NOT_SAME_DAY', '당일 충전 건만 취소할 수 있습니다.')
    }
    if (found.balance < target.amount) {
      throw new ApiError(409, 'CHARGE_ALREADY_USED', '잔액이 부족해 취소할 수 없어요. 먼저 사용 건을 취소해 주세요.', [], {
        balance: found.balance,
        chargeAmount: target.amount,
      })
    }
  }

  const signedAmount = kind === 'use' ? target.amount : -target.amount
  const balanceAfter = found.balance + signedAmount
  const reversal: TransactionResponse = {
    id: crypto.randomUUID(),
    type: types.reversal,
    amount: target.amount,
    signedAmount,
    balanceAfter,
    memo: reason,
    performedAt: new Date().toISOString(),
    performedByName,
    cancelled: false,
    reversesId: target.id,
    cancellable: false,
  }
  found.balance = balanceAfter
  if (kind === 'use') found.active = true
  // 원거래는 지우지 않고 '취소됨'으로만 바꾼다. 이미 응답으로 나간 객체를 고치지 않도록 새 객체로 바꿔 넣는다
  found.entries = [
    reversal,
    ...found.entries.map((e) => (e.id === target.id ? { ...e, cancelled: true, cancellable: false } : e)),
  ]
  return reversal
}

/** 가짜 서버의 현재 잔액(시나리오 준비용). 없는 고객이면 null */
export function mockBalanceOf(customerId: string): number | null {
  return MOCK_CUSTOMERS.find((c) => c.id === customerId)?.balance ?? null
}

// 가짜 서버만 아는 연락처 원문(숫자만). 화면으로 나가는 응답에는 넣지 않는다
const MOCK_PHONE_DIGITS = new Map<string, string>([
  [mockId(1), '01033814821'],
  [mockId(51), '01055213390'],
  [mockId(2), '01024701093'],
  [mockId(53), '01024701093'],
  [mockId(54), '01090127781'],
  [mockId(55), '01090127781'],
])

const PHONE_INPUT_PATTERN = /^(?:[0-9]-?){8,10}[0-9]$/
const NAME_MAX_LENGTH = 20
const MEMO_MAX_LENGTH = 200

/** 서버 PhoneNumberMasker 와 같은 모양으로 가린다: 02 는 앞 2자리, 그 밖은 앞 3자리 + 가운데 * + 뒤 4자리 */
function maskMockPhone(digits: string): string {
  const prefix = digits.startsWith('02') ? 2 : 3
  const suffixStart = digits.length - 4
  return `${digits.slice(0, prefix)}-${'*'.repeat(suffixStart - prefix)}-${digits.slice(suffixStart)}`
}

/**
 * 고객 등록 목 — 서버(CustomerRegisterRequest·Customer·CustomerService)와 같은 순서로 판단한다:
 * 형식 오류 400 INVALID_INPUT(fieldErrors) → 같은 번호(비활성 포함) 고객이 있고 확인 표시가 없으면 409 DUPLICATE_PHONE
 * (details.existingCustomers — 활성 먼저·최근 등록순, 연락처 없음) → 아니면 잔액 0원 고객을 만든다.
 */
export function registerMockCustomer({
  name,
  phone,
  memo,
  confirmDuplicatePhone,
}: CustomerRegisterRequest): Promise<CustomerRegisterResponse> {
  return delayed(() => {
    const trimmedName = name.trim()
    const trimmedMemo = memo?.trim() ? memo.trim() : null
    const fieldErrors: FieldError[] = []
    if (!trimmedName) fieldErrors.push({ field: 'name', message: '이름을 입력해 주세요.' })
    else if (Array.from(trimmedName).length > NAME_MAX_LENGTH)
      fieldErrors.push({ field: 'name', message: `이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.` })
    if (!phone.trim()) fieldErrors.push({ field: 'phone', message: '연락처를 입력해 주세요.' })
    else if (!PHONE_INPUT_PATTERN.test(phone))
      fieldErrors.push({
        field: 'phone',
        message: '연락처는 숫자 9–11자리여야 합니다. 하이픈(-)은 숫자 사이에만 쓸 수 있습니다.',
      })
    if (trimmedMemo && Array.from(trimmedMemo).length > MEMO_MAX_LENGTH)
      fieldErrors.push({ field: 'memo', message: `메모는 ${MEMO_MAX_LENGTH}자 이하여야 합니다.` })
    if (fieldErrors.length > 0) {
      throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.', fieldErrors)
    }

    const digits = phone.replace(/-/g, '')
    const samePhone = MOCK_CUSTOMERS.filter((c) => MOCK_PHONE_DIGITS.get(c.id) === digits).sort(
      (a, b) => Number(b.active) - Number(a.active) || b.createdAt.localeCompare(a.createdAt),
    )
    if (samePhone.length > 0 && !confirmDuplicatePhone) {
      throw new ApiError(409, 'DUPLICATE_PHONE', '같은 번호의 고객이 있습니다.', [], {
        existingCustomers: samePhone.map(({ id, name: n, active }) => ({ id, name: n, active })),
      })
    }

    const created: MockCustomer = {
      id: crypto.randomUUID(),
      name: trimmedName,
      maskedPhone: maskMockPhone(digits),
      balance: 0,
      createdAt: new Date().toISOString(),
      active: true,
      entries: [],
    }
    MOCK_PHONE_DIGITS.set(created.id, digits)
    // 목록은 최근 등록순이라 맨 앞에 넣는다
    MOCK_CUSTOMERS.unshift(created)
    return {
      id: created.id,
      name: created.name,
      maskedPhone: created.maskedPhone,
      balance: created.balance,
      memo: trimmedMemo,
      createdAt: created.createdAt,
    }
  })
}
