package com.creditbook.global;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.support.RequiresDocker;

// BUG-5 (CB-62) 재현 — 주소에 // 나 정리되지 않은 .. 가 있으면 보안 필터의 경로 검사가 거절하는데,
// 그 응답이 표준 오류 형식이 아니라 프레임워크 기본 형식(timestamp·status·error·path)으로 나갔다.
// MockMvc 는 주소를 미리 정리해 버려 재현되지 않아서, 실제 포트로 띄운 서버에 주소를 그대로 보낸다.
// 경로 검사는 인증보다 앞 단계라 로그인 없이 보낸다.
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Tag("REQ-27")
@Tag("TC-24")
class RejectedPathApiIntegrationTest {

	// 응답 본문에 있으면 안 되는 내부 정보 흔적 (SecurityExposureApiIntegrationTest 와 같은 목록)
	private static final List<String> INTERNAL_TRACES = List.of(
			"exception", "at com.", "at org.", "select", "insert", ".java", "creditbook/", "\\", "trace");

	@LocalServerPort
	int port;

	// 버그 리포트의 재현 주소 두 가지 — 빈 칸이 낀 주소, 위로 올라가는 주소
	static List<String[]> rejectedPaths() {
		String id = UUID.randomUUID().toString();
		return List.of(
				new String[] { "POST", "/api/customers//charges" },
				new String[] { "GET", "/api/customers/" + id + "/../../settings/ledger-input" });
	}

	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("rejectedPaths")
	@DisplayName("주소에 // 나 정리되지 않은 .. 가 있으면 400 표준 오류 형식으로 거절한다")
	void rejected_path_returns_standard_error_format(String method, String path) throws Exception {
		// given 주소를 손대지 않고 그대로 보낸다 (URI.create 는 // 와 .. 를 정리하지 않는다)
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(method.equals("POST") ? "{}" : ""))
				.build();

		// when
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		// then 400, 표준 형식 {code, message} — 프레임워크 기본 형식 칸(timestamp·error·path)은 없다
		String body = response.body();
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(body)
				.contains("\"code\":\"INVALID_REQUEST_PATH\"")
				.contains("\"message\":\"요청 주소를 확인해 주세요.\"")
				.doesNotContain("\"timestamp\"", "\"error\"", "\"path\"");
		// then 내부 정보 흔적도 없다
		assertThat(body).doesNotContainIgnoringCase(INTERNAL_TRACES.toArray(String[]::new));
	}

}
