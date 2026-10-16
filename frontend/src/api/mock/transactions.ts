import { ApiError } from '../client'
import type { TransactionListParams, TransactionListResponse } from '../transactions'
import { delayed, mockEntriesOf } from './customers'

// 화면 개발용 거래 이력 목. 고객 목(mock/customers.ts)의 장부를 그대로 페이지로 나눠 준다 — 금액·잔액·취소 가능 여부는
// 장부에 적힌 값 그대로다. 확인용 고객: 박서연 …001(10건), 정하은 …005(28건 — 페이지 나눔), 한지민 …052(0건).
// 형식이 UUID 가 아닌 id·잘못된 page/size 는 400 INVALID_INPUT, 없는 UUID 는 404 CUSTOMER_NOT_FOUND.

export function getMockTransactions(
  customerId: string,
  { page, size }: TransactionListParams,
  signal?: AbortSignal,
): Promise<TransactionListResponse> {
  return delayed(() => {
    if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1) {
      throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.')
    }
    const entries = mockEntriesOf(customerId)
    const start = page * size
    return {
      content: entries.slice(start, start + size),
      page,
      size,
      totalElements: entries.length,
      totalPages: Math.ceil(entries.length / size),
      hasNext: start + size < entries.length,
    }
  }, signal)
}
