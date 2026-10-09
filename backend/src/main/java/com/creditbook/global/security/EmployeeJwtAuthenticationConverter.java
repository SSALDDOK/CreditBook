package com.creditbook.global.security;

import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.creditbook.auth.domain.EmployeeAuthStatus;
import com.creditbook.auth.domain.EmployeeRepository;

/**
 * 서명·만료 검증을 통과한 토큰을 인증 정보로 바꾼다. 요청마다 DB 를 한 번 조회해 다음을 함께 확인한다.
 * <ul>
 * <li>직원이 있고 활성 상태인가 (비활성화되면 이미 받은 토큰도 다음 요청부터 401)</li>
 * <li>로그아웃으로 폐기된 토큰이 아닌가</li>
 * <li>권한(ROLE_ADMIN/ROLE_STAFF)은 토큰이 아니라 이 조회의 DB 역할로 붙인다</li>
 * </ul>
 * 검증과 권한 부여를 같은 조회 결과로 해야 요청당 1쿼리가 되므로 별도 토큰 검증기 대신 여기서 확인한다.
 * 거절은 {@link InvalidBearerTokenException} 으로 던져 401 이 된다 — sub·jti 가 UUID 가 아니어도 500 이 아니라 401 이다.
 * 사유 문구는 로그에만 쓰이며 토큰 원문을 담지 않는다.
 */
@Component
public class EmployeeJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final EmployeeRepository employeeRepository;

	public EmployeeJwtAuthenticationConverter(EmployeeRepository employeeRepository) {
		this.employeeRepository = employeeRepository;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		UUID employeeId = parseUuid(jwt.getSubject(), "sub");
		UUID jti = parseUuid(jwt.getId(), "jti");
		EmployeeAuthStatus status = employeeRepository.findAuthStatus(employeeId, jti)
				.orElseThrow(() -> new InvalidBearerTokenException("employee not found"));
		if (!status.active()) {
			throw new InvalidBearerTokenException("employee inactive");
		}
		if (status.tokenRevoked()) {
			throw new InvalidBearerTokenException("token revoked");
		}
		return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(status.role().authority())),
				employeeId.toString());
	}

	private static UUID parseUuid(String value, String claim) {
		if (value == null) {
			throw new InvalidBearerTokenException(claim + " missing");
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidBearerTokenException(claim + " is not a UUID");
		}
	}

}
