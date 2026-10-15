import { useId, useState } from 'react'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { selectedMemoChips, toggleMemoChip } from './memoChips'

// 메모 입력 — 문구 칩(설정값) + 자유 입력칸. 충전·사용 모달이 같이 쓴다(칩 규칙은 memoChips.ts, REQ-31).
// 칩을 눌러도 입력칸은 직접 고칠 수 있다. 최대 길이를 넘게 되는 칩은 붙이지 않고 안내한다. 직접 입력도 maxLength 에서 멈춘다.

export function MemoField({
  label,
  value,
  onChange,
  presets,
  maxLength,
  hint,
  error,
  disabled,
  testIdPrefix,
}: {
  label: string
  value: string
  onChange: (memo: string) => void
  /** 문구 칩 — 서버 설정값(GET /api/settings/ledger-input) */
  presets: string[]
  /** 메모 최대 길이 — 서버 설정값 */
  maxLength: number
  hint?: string
  /** 서버 fieldErrors 등 */
  error?: string | null
  disabled?: boolean
  testIdPrefix: string
}) {
  const inputId = useId()
  const hintId = useId()
  const noticeId = useId()
  const errorId = useId()
  // 칩을 붙이지 못한 안내. 다음 입력·칩 조작 때 지운다
  const [rejectedChip, setRejectedChip] = useState<string | null>(null)

  const selected = new Set(selectedMemoChips(value, presets))
  const atLimit = value.length >= maxLength
  const notice = rejectedChip
    ? `메모는 ${maxLength}자까지 입력할 수 있어 '${rejectedChip}'을(를) 붙이지 못했습니다.`
    : atLimit
      ? `메모는 ${maxLength}자까지 입력할 수 있습니다.`
      : null
  const describedBy =
    [hint ? hintId : null, notice ? noticeId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined

  function handleChip(chip: string) {
    const result = toggleMemoChip(value, chip, maxLength)
    setRejectedChip(result.rejected ? chip : null)
    if (!result.rejected) onChange(result.memo)
  }

  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={inputId} className="text-base font-semibold">
        {label}
      </label>
      {presets.length > 0 && (
        <div className="flex flex-wrap gap-2" role="group" aria-label="메모 문구">
          {presets.map((chip) => {
            const on = selected.has(chip)
            return (
              <button
                key={chip}
                type="button"
                aria-pressed={on}
                disabled={disabled}
                onClick={() => handleChip(chip)}
                data-testid={`${testIdPrefix}-memo-chip`}
                data-chip={chip}
                className={cn(
                  'h-11 min-w-20 rounded-full border px-5 text-base font-semibold transition-colors',
                  'focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none disabled:opacity-50',
                  on ? 'border-primary bg-primary/10 text-primary' : 'bg-background hover:bg-muted',
                )}
              >
                {chip}
              </button>
            )
          })}
        </div>
      )}
      <Input
        id={inputId}
        type="text"
        value={value}
        onChange={(e) => {
          setRejectedChip(null)
          onChange(e.target.value)
        }}
        maxLength={maxLength}
        disabled={disabled}
        autoComplete="off"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        data-testid={`${testIdPrefix}-memo-input`}
        className="h-12 rounded-xl px-4 text-lg md:text-lg"
      />
      {hint && (
        <p id={hintId} className="text-sm text-muted-foreground">
          {hint}
        </p>
      )}
      {notice && (
        <p id={noticeId} role="status" className="text-sm text-amber-700" data-testid={`${testIdPrefix}-memo-notice`}>
          {notice}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className="text-base text-destructive" data-testid={`${testIdPrefix}-memo-error`}>
          {error}
        </p>
      )}
    </div>
  )
}
