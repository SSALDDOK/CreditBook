import { Component, type ReactNode } from 'react'

type Props = { children: ReactNode }
type State = { hasError: boolean }

/** 렌더 중 예외로 화면 전체가 비는 것을 막는다. 예외 내용은 화면에 내보내지 않는다. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  render() {
    if (this.state.hasError) {
      return (
        <p role="alert" data-testid="app-error" className="p-8">
          일시적인 오류가 발생했습니다. 새로고침해 주세요.
        </p>
      )
    }
    return this.props.children
  }
}
