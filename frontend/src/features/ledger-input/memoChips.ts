// 메모 문구 칩 규칙 (REQ-31) — 충전 모달(SCR-6)과 사용 모달(SCR-7)이 같은 함수를 쓴다.
// 메모는 쉼표로 나눈 조각들로 본다: 칩을 누르면 끝에 ", "로 붙고, 같은 칩을 다시 누르면 그 조각이 빠진다.
// 나눌 때 앞뒤 공백을 지우고 빈 조각을 버리므로 양끝·중간에 쉼표가 남지 않는다.
//
// 예 (maxLength 200):
//   toggleMemoChip('', '현금')              → { memo: '현금', rejected: false }
//   toggleMemoChip('현금', '카드')          → { memo: '현금, 카드', rejected: false }
//   toggleMemoChip('현금, 카드', '현금')    → { memo: '카드', rejected: false }
//   toggleMemoChip('단골 ,, 현금,', '현금') → { memo: '단골', rejected: false }
//   toggleMemoChip('가'.repeat(199), '현금') → { memo: (그대로), rejected: true }   // 붙이면 200자를 넘는다
//   selectedMemoChips('카드, 단골', ['현금', '카드', '계좌이체']) → ['카드']

/** 메모를 쉼표로 나눠 앞뒤 공백을 지운 조각들(빈 조각 제외) */
export function splitMemo(memo: string): string[] {
  return memo
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part !== '')
}

export function joinMemo(parts: string[]): string {
  return parts.join(', ')
}

export type ToggleMemoChipResult = {
  memo: string
  /** true 면 붙이면 최대 길이를 넘어서 붙이지 않았다(memo 는 그대로) */
  rejected: boolean
}

/** 칩을 누른 결과. 이미 있는 칩이면 빼고, 없으면 끝에 붙인다(최대 길이를 넘으면 붙이지 않는다) */
export function toggleMemoChip(memo: string, chip: string, maxLength: number): ToggleMemoChipResult {
  const parts = splitMemo(memo)
  if (parts.includes(chip)) {
    return { memo: joinMemo(parts.filter((part) => part !== chip)), rejected: false }
  }
  const next = joinMemo([...parts, chip])
  if (next.length > maxLength) return { memo, rejected: true }
  return { memo: next, rejected: false }
}

/** 메모에 들어 있는 칩 문구(칩 목록 순서). 칩 강조 표시와 입력 복구 저장에 쓴다 */
export function selectedMemoChips(memo: string, presets: readonly string[]): string[] {
  const parts = new Set(splitMemo(memo))
  return presets.filter((preset) => parts.has(preset))
}
