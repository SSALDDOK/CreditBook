import type { CustomerListResponse, CustomerResponse, CustomerSearchParams } from '../customers'

// 화면 개발용 목 데이터. 이름은 가상이고, 연락처는 서버 응답과 같은 "이미 가린" 형태로만 둔다.
// 잔액은 서버가 준 값처럼 고정 값으로 둔다(화면에서 계산하지 않는다).

const FIRST: Array<Omit<CustomerResponse, 'id'>> = [
  { name: '박서연', maskedPhone: '010-****-4821', balance: 45500, createdAt: '2026-10-05T02:10:00Z' },
  { name: '이준호', maskedPhone: '010-****-1093', balance: 50000, createdAt: '2026-10-02T05:30:00Z' },
  { name: '최다은', maskedPhone: '010-****-7740', balance: 12200, createdAt: '2026-09-30T01:00:00Z' },
  { name: '강민석', maskedPhone: '010-****-2256', balance: 0, createdAt: '2026-09-26T07:45:00Z' },
  { name: '정하은', maskedPhone: '010-****-6612', balance: 128000, createdAt: '2026-09-19T03:20:00Z' },
]

const FAMILY = ['김', '이', '박', '최', '정', '강', '조', '윤', '장', '임', '한', '오', '서', '신', '권']
const GIVEN = ['지우', '서준', '하린', '도윤', '수아', '예준', '지민', '시우', '유나', '민준', '채원', '현우', '소율']
const BALANCES = [0, 3000, 8500, 15000, 22000, 30000, 47500, 60000, 99000, 150000, 210000, 300000, 412300]
const SUFFIXES = [
  '3390', '5107', '8823', '0412', '2968', '7731', '1185', '6604', '9257', '4479', '3016', '5590', '2841', '7368',
  '0925', '6153', '8410', '1732', '4086', '9671', '3548', '5214', '7902', '2367', '6839', '0158', '8745', '1496',
  '4623', '9380', '3071', '5862', '7194', '2509', '6247', '0836', '8371', '1950', '4718', '9063', '3625',
]

const DAY_MS = 24 * 60 * 60 * 1000
const OLDEST_FIRST_ROW = Date.parse(FIRST[FIRST.length - 1].createdAt)

const MOCK_CUSTOMERS: CustomerResponse[] = [
  ...FIRST.map((c, i) => ({ id: `00000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`, ...c })),
  ...SUFFIXES.map((suffix, i) => ({
    id: `00000000-0000-4000-8000-${String(FIRST.length + i + 1).padStart(12, '0')}`,
    name: FAMILY[i % FAMILY.length] + GIVEN[(i * 5) % GIVEN.length],
    maskedPhone: `010-****-${suffix}`,
    balance: BALANCES[(i * 7) % BALANCES.length],
    createdAt: new Date(OLDEST_FIRST_ROW - (i + 1) * 2 * DAY_MS).toISOString(),
  })),
]

const PHONE_QUERY = /^[\d\s-]+$/
const MOCK_DELAY_MS = 250

function filter(q: string | undefined): CustomerResponse[] {
  const trimmed = q?.trim() ?? ''
  if (!trimmed) return MOCK_CUSTOMERS
  // 서버와 같은 규칙: 숫자(하이픈·공백 허용)만이면 연락처 뒷자리, 그 밖은 이름 일부
  if (PHONE_QUERY.test(trimmed)) {
    const digits = trimmed.replace(/\D/g, '')
    return MOCK_CUSTOMERS.filter((c) => c.maskedPhone.replace(/\D/g, '').endsWith(digits))
  }
  return MOCK_CUSTOMERS.filter((c) => c.name.includes(trimmed))
}

export function searchMockCustomers(
  { q, page = 0, size = 20 }: CustomerSearchParams,
  signal?: AbortSignal,
): Promise<CustomerListResponse> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      const matched = filter(q)
      const start = page * size
      resolve({
        content: matched.slice(start, start + size),
        page,
        size,
        totalElements: matched.length,
        totalPages: Math.ceil(matched.length / size),
        hasNext: start + size < matched.length,
      })
    }, MOCK_DELAY_MS)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('Aborted', 'AbortError'))
    })
  })
}
