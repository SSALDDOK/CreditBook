# 운영 배포용 이미지 (Render). 빌드 맥락은 backend/ 폴더다.
# 1단계에서 실행 가능한 jar 를 만들고, 2단계는 JRE 와 jar 만 담는다 — 소스·Gradle 캐시·비밀값은 최종 이미지에 들어가지 않는다.
# 접속 정보·JWT 서명 키는 이미지에 넣지 않고 배포처 환경변수로만 받는다 (CLAUDE.md 절대 금지 #5)

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
# 테스트는 CI 필수 검사(backend build & test)가 이미 돌렸다. 여기서는 jar 만 만든다
RUN ./gradlew --no-daemon bootJar \
    && cp build/libs/creditbook-*-SNAPSHOT.jar app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 creditbook
COPY --from=build /workspace/app.jar app.jar
USER creditbook
# 무료 인스턴스 메모리가 작아 힙을 컨테이너 메모리 비율로 잡는다. 배포처 환경변수 JAVA_TOOL_OPTIONS 로 덮어쓸 수 있다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
