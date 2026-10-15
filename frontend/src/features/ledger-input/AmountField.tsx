import type { Ref } from 'react'
import { useId } from 'react'
import { Input } from '@/components/ui/input'
import { formatWon } from '@/lib/format'
import { cn } from '@/lib/utils'
import { formatAmountInput, normalizeAmountInput } from './amountInput'

// 금액 입력칸 — 빠른 금액 칩 + 숫자 칸(3자리 콤마 자동) + 안내 + 오류. 충전·사용 모달이 같이 쓴다.
// value 는 콤마 없는 입력 문자열(normalizeAmountInput 결과)이다. 숫자로 바꾸고 검사하는 것은 호출하는 쪽(checkAmount)이 한다.

export function AmountField({
  label,
  value,
  onChange,
  quickAmounts = [],
  hint,
  error,
  disabled,
  inputRef,
  testIdPrefix,
}: {
  label: string
  value: string
  onChange: (normalized: string) => void
  /** 빠른 금액 칩(원). 누르면 칸을 그 금액으로 채운다 */
  quickAmounts?: number[]
  hint?: string
  /** 보여 줄 오류 문구. 있으면 칸이 빨간 테두리(aria-invalid)가 된다 */
  error?: string | null
  disabled?: boolean
  inputRef?: Ref<HTMLInputElement>
  /** data-testid 앞부분 (예: 'charge' → charge-amount-input) */
  testIdPrefix: string
}) {
  const inputId = useId()
  const hintId = useId()
  const errorId = useId()
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined

  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={inputId} className="text-base font-semibold">
        {label}
      </label>
      {quickAmounts.length > 0 && (
        <div className="flex flex-wrap gap-2" role="group" aria-label="빠른 금액">
          {quickAmounts.map((amount) => {
            const selected = value === String(amount)
            return (
              <button
                key={amount}
                type="button"
                aria-pressed={selected}
                disabled={disabled}
                onClick={() => onChange(String(amount))}
                data-testid={`${testIdPrefix}-quick-amount`}
                data-amount={amount}
                className={cn(
                  'h-11 min-w-28 rounded-full border px-5 text-base font-semibold tabular-nums transition-colors',
                  'focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none disabled:opacity-50',
                  selected ? 'border-primary bg-primary text-primary-foreground' : 'bg-background hover:bg-muted',
                )}
              >
                {formatWon(amount)}
              </button>
            )
          })}
        </div>
      )}
      <div className="relative">
        <Input
          ref={inputRef}
          id={inputId}
          type="text"
          inputMode="numeric"
          autoComplete="off"
          value={formatAmountInput(value)}
          onChange={(e) => onChange(normalizeAmountInput(e.target.value))}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          data-testid={`${testIdPrefix}-amount-input`}
          className="h-14 rounded-xl pr-12 text-right text-2xl font-bold tabular-nums md:text-2xl aria-invalid:border-2"
        />
        <span aria-hidden="true" className="pointer-events-none absolute top-1/2 right-4 -translate-y-1/2 text-xl">
          원
        </span>
      </div>
      {hint && (
        <p id={hintId} className="text-sm text-muted-foreground" data-testid={`${testIdPrefix}-amount-hint`}>
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className="text-base text-destructive" data-testid={`${testIdPrefix}-amount-error`}>
          {error}
        </p>
      )}
    </div>
  )
}
