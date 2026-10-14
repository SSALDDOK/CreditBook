import { ApiError } from '../client'
import type {
  CustomerDetailResponse,
  CustomerListResponse,
  CustomerResponse,
  CustomerSearchParams,
  RecentLedgerEntryResponse,
} from '../customers'

// 화면 개발용 목 데이터. 이름은 가상이고, 연락처는 서버 응답과 같은 "이미 가린" 형태로만 둔다.
// 잔액·변동 후 잔액(balanceAfter)·부호 붙은 금액(signedAmount)은 서버가 준 값처럼 고정 값으로 적어 둔다
// (화면도 목도 금액을 계산하지 않는다). 최근 거래의 잔액 흐름은 손으로 맞춰 적었다.
//
// 상세 화면 확인용 고객 (화면설계 SCR-5 이미지 기준)
// - 박서연  …001  활성, 잔액 45,500원, 최근 거래 5건
// - 이준호  …002  활성, 잔액 0원 → [사용] 비활성 + "잔액이 없습니다"
// - 오지훈  …051  비활성, 잔액 0원 → 안내 배너·'비활성' 배지 (목록에서는 '비활성 포함'을 켜야 보인다)
// - 한지민  …052  활성, 잔액 0원, 거래 0건 → "마지막 거래" 줄 숨김, 최근 거래 빈 안내
// 그 밖의 고객은 최근 거래 없이(recentEntries: []) 상세를 돌려준다.
// 형식이 UUID 가 아닌 id 는 400 INVALID_INPUT, 없는 UUID 는 404 CUSTOMER_NOT_FOUND.

type MockCustomer = CustomerResponse & { recentEntries: RecentLedgerEntryResponse[] }

function mockId(n: number): string {
  return `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
}

function entryId(n: number): string {
  return `00000000-0000-4000-a000-${String(n).padStart(12, '0')}`
}

const PARK_ENTRIES: RecentLedgerEntryResponse[] = [
  { id: entryId(105), type: 'USE', amount: 4500, signedAmount: -4500, balanceAfter: 45500, memo: '아메리카노 2잔', performedAt: '2026-10-15T05:22:00Z', performedByName: '김직원', cancelled: false },
  { id: entryId(104), type: 'CHARGE', amount: 50000, signedAmount: 50000, balanceAfter: 50000, memo: '현금', performedAt: '2026-09-18T00:14:00Z', performedByName: '박사장', cancelled: false },
  { id: entryId(103), type: 'USE', amount: 5200, signedAmount: -5200, balanceAfter: 0, memo: '라떼 1잔', performedAt: '2026-09-12T08:02:00Z', performedByName: '김직원', cancelled: false },
  { id: entryId(102), type: 'USE', amount: 4800, signedAmount: -4800, balanceAfter: 5200, memo: '케이크 1조각', performedAt: '2026-09-03T06:40:00Z', performedByName: '김직원', cancelled: false },
  { id: entryId(101), type: 'CHARGE', amount: 10000, signedAmount: 10000, balanceAfter: 10000, memo: '계좌이체', performedAt: '2026-08-28T01:05:00Z', performedByName: '박사장', cancelled: false },
]

const LEE_ENTRIES: RecentLedgerEntryResponse[] = [
  { id: entryId(205), type: 'USE', amount: 9000, signedAmount: -9000, balanceAfter: 0, memo: '아메리카노 2잔', performedAt: '2026-10-15T05:22:00Z', performedByName: '김직원', cancelled: false },
  { id: entryId(204), type: 'USE', amount: 6500, signedAmount: -6500, balanceAfter: 9000, memo: '샌드위치 1개', performedAt: '2026-10-04T03:15:00Z', performedByName: '김직원', cancelled: false },
  { id: entryId(203), type: 'CHARGE', amount: 10000, signedAmount: 10000, balanceAfter: 15500, memo: '카드', performedAt: '2026-10-02T02:30:00Z', performedByName: '박사장', cancelled: false },
  { id: entryId(202), type: 'USE', amount: 4500, signedAmount: -4500, balanceAfter: 5500, memo: '라떼 1잔', performedAt: '2026-09-24T23:50:00Z', performedByName: '김직원', cancelled: false },
  { id: entryId(201), type: 'CHARGE', amount: 10000, signedAmount: 10000, balanceAfter: 10000, memo: '현금', performedAt: '2026-09-20T00:02:00Z', performedByName: '박사장', cancelled: false },
]

const OH_ENTRIES: RecentLedgerEntryResponse[] = [
  { id: entryId(305), type: 'USE', amount: 4500, signedAmount: -4500, balanceAfter: 0, memo: '아메리카노 1잔', performedAt: '2026-08-30T07:10:00Z', performedByName: '김직원', cancelled: false },
  { id: entryId(304), type: 'USE', amount: 10300, signedAmount: -10300, balanceAfter: 4500, memo: '라떼 2잔', performedAt: '2026-08-21T04:45:00Z', performedByName: '김직원', cancelled: false },
  { id: entryId(303), type: 'USE', amount: 4800, signedAmount: -4800, balanceAfter: 14800, memo: '케이크 1조각', performedAt: '2026-08-14T06:00:00Z', performedByName: '김직원', cancelled: false },
  { id: entryId(302), type: 'USE', amount: 10400, signedAmount: -10400, balanceAfter: 19600, memo: '라떼 2잔', performedAt: '2026-08-10T04:45:00Z', performedByName: '김직원', cancelled: false },
  { id: entryId(301), type: 'CHARGE', amount: 30000, signedAmount: 30000, balanceAfter: 30000, memo: '현금', performedAt: '2026-08-05T01:20:00Z', performedByName: '박사장', cancelled: false },
]

const FIRST: Array<Omit<MockCustomer, 'id'>> = [
  { name: '박서연', maskedPhone: '010-****-4821', balance: 45500, createdAt: '2026-10-05T02:10:00Z', active: true, recentEntries: PARK_ENTRIES },
  { name: '이준호', maskedPhone: '010-****-1093', balance: 0, createdAt: '2026-10-02T05:30:00Z', active: true, recentEntries: LEE_ENTRIES },
  { name: '최다은', maskedPhone: '010-****-7740', balance: 12200, createdAt: '2026-09-30T01:00:00Z', active: true, recentEntries: [] },
  { name: '강민석', maskedPhone: '010-****-2256', balance: 0, createdAt: '2026-09-26T07:45:00Z', active: true, recentEntries: [] },
  { name: '정하은', maskedPhone: '010-****-6612', balance: 128000, createdAt: '2026-09-19T03:20:00Z', active: true, recentEntries: [] },
]

// 목록 어디에 끼어도 되도록 id 를 따로 둔다(등록일 순으로 다시 정렬한다)
const EXTRA: MockCustomer[] = [
  { id: mockId(51), name: '오지훈', maskedPhone: '010-****-3390', balance: 0, createdAt: '2026-08-01T02:00:00Z', active: false, recentEntries: OH_ENTRIES },
  { id: mockId(52), name: '한지민', maskedPhone: '010-****-5047', balance: 0, createdAt: '2026-10-15T00:30:00Z', active: true, recentEntries: [] },
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
    recentEntries: [],
  })),
  ...EXTRA,
].sort((a, b) => b.createdAt.localeCompare(a.createdAt))

const PHONE_QUERY = /^[\d\s-]+$/
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const MOCK_DELAY_MS = 250

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

function toListItem({ recentEntries: _entries, ...item }: MockCustomer): CustomerResponse {
  return item
}

function delayed<T>(produce: () => T, signal?: AbortSignal): Promise<T> {
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

export function getMockCustomer(customerId: string, signal?: AbortSignal): Promise<CustomerDetailResponse> {
  return delayed(() => {
    if (!UUID_PATTERN.test(customerId)) {
      throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.')
    }
    const found = MOCK_CUSTOMERS.find((c) => c.id === customerId)
    if (!found) {
      throw new ApiError(404, 'CUSTOMER_NOT_FOUND', '고객을 찾을 수 없습니다.')
    }
    return { ...found }
  }, signal)
}
