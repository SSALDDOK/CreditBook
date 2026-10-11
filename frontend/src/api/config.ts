// 목 데이터 / 실제 API 전환은 이 한 곳에서 한다.
// VITE_USE_MOCK 을 지정하면 그 값을 따르고, 지정하지 않으면 개발 서버(npm run dev)만 목 데이터, 운영 빌드는 실제 API 다.
// 운영 빌드가 설정 누락만으로 가짜 고객을 보여 주는 일이 없게 하려는 기본값이다.
const mockFlag = import.meta.env.VITE_USE_MOCK
export const USE_MOCK = mockFlag === undefined || mockFlag === '' ? import.meta.env.DEV : mockFlag === 'true'
