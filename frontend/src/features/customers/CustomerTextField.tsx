import { useId, type ReactNode, type RefObject } from 'react'
import { Input } from '@/components/ui/input'

/**
 * 고객 등록(SCR-4)·고객 정보 수정(SCR-9)이 같이 쓰는 입력 칸 — 라벨(필수·선택 표시) + 입력 + 안내 + 칸 오류.
 * 안내·라벨 옆 설명·오류는 aria-describedby 로 입력에 이어 Playwright getByLabel 로 찾을 수 있게 한다.
 * testId 를 받아 입력은 `${testId}-input`, 오류는 `${testId}-error` 로 붙인다.
 */
export function CustomerTextField({
  label,
  required = false,
  badge = required ? 'required' : 'optional',
  labelNote,
  hint,
  value,
  onChange,
  error,
  inputRef,
  testId,
  counter,
  ...inputProps
}: {
  label: string
  required?: boolean
  /** 라벨 옆 표시 — 기본은 required 에 따라 '필수'·'선택'. 'none' 이면 표시하지 않는다(수정 화면의 연락처) */
  badge?: 'required' | 'optional' | 'none'
  /** 라벨 옆 회색 설명(예: 수정 화면의 '지금 번호 … · 바꿀 때만 입력'). 입력의 설명으로도 읽힌다 */
  labelNote?: ReactNode
  hint: string
  value: string
  onChange: (value: string) => void
  error?: string
  inputRef: RefObject<HTMLInputElement | null>
  testId: string
  counter?: ReactNode
  placeholder?: string
  type?: 'text' | 'tel'
  inputMode?: 'text' | 'tel'
  maxLength?: number
  autoComplete?: string
  disabled?: boolean
}) {
  const inputId = useId()
  const noteId = useId()
  const hintId = useId()
  const errorId = useId()
  const describedBy = [labelNote ? noteId : null, hintId, error ? errorId : null].filter(Boolean).join(' ')

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between gap-3">
        <div className="flex min-w-0 flex-wrap items-baseline gap-x-3">
          <label htmlFor={inputId} className="text-base font-bold">
            {label}
            {badge === 'required' && <span className="ml-1.5 text-sm font-semibold text-destructive">필수</span>}
            {badge === 'optional' && <span className="ml-1.5 text-sm font-normal text-muted-foreground">선택</span>}
          </label>
          {labelNote && (
            <span id={noteId} className="text-sm text-muted-foreground tabular-nums" data-testid={`${testId}-note`}>
              {labelNote}
            </span>
          )}
        </div>
        {counter}
      </div>
      <Input
        ref={inputRef}
        id={inputId}
        type={inputProps.type ?? 'text'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        required={required}
        aria-required={required || undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        data-testid={`${testId}-input`}
        {...inputProps}
        className="h-11 rounded-xl px-4 text-base md:text-base"
      />
      <p id={hintId} className="text-sm text-muted-foreground">
        {hint}
      </p>
      {error && (
        <p id={errorId} role="alert" className="text-sm font-semibold text-destructive" data-testid={`${testId}-error`}>
          {error}
        </p>
      )}
    </div>
  )
}
