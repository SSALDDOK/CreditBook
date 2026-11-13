package com.creditbook.store.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 초대 코드 저장소. 구현은 infrastructure 에 있다. 코드는 지우지 않는다 — 삭제 메서드를 두지 않는다. 발급은 운영자 절차라 추가 메서드도 없다.
 */
public interface StoreInviteCodeRepository {

	/**
	 * 해시가 같고 사용 중이며 한도가 남은 코드의 사용 횟수를 1 올리고 그 id 를 돌려준다. 확인과 차감을 조건부 UPDATE 한 문장으로 한다 —
	 * 같은 코드로 동시에 가입하면 행 잠금으로 줄을 서고, 한도를 넘는 요청은 0행이 된다. 트랜잭션 안에서만 부른다
	 * (가입이 뒤에서 실패하면 차감도 함께 되돌아간다).
	 *
	 * @param codeHash {@link StoreInviteCode#hashOf} 값
	 * @return 차감한 코드 id. 없는 코드·사용 중지·한도 소진이면 빈 값 (구분하지 않는다)
	 */
	Optional<UUID> claimUse(String codeHash);

}
