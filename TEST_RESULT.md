# 검증 결과

- 2026-09-28 CTP-001 컴파일: 통과. `compileJava compileClientJava --no-daemon`으로 폐봉수대 맵·보스·화로 전환 코드를 확인했다. 실제 Minecraft 조작 검증은 미실행이다.

> 문서 ID: EVD-001 · 분류: 현황·증적 · 상태: 증적 기록

- 2026-09-28 전체 빌드: 통과. `server-project/gradlew.bat build --no-daemon`이 성공했다. 컴파일·클라이언트 컴파일·표준 시험 작업은 통과했다.
- 2026-09-28 DB 검증 재실행: 미실행. `applyCurrentMigrations`와 연계 검증은 현재 환경에 `DALMARU_DB_PASSWORD`가 없어 시작 단계에서 중단됐다. 이전 통과 기록을 최신 재현 증거로 대체하지 않는다.
- 시각: 2026-09-26 02:25:00 +09:00
- 기력 HUD: `compileClientJava` 통과. 실제 localhost 클라이언트를 새로 실행해 시각 확인 대기.
- 퀘스트 단축키: 빌드 통과. `U` 키 전용 화면으로 분리하고 보이는 localhost 클라이언트를 재시작했다. 인게임 화면 확인 대기.
- 전체 저장소·전투 검증: 통과. PostgreSQL 연결, 계정 세션, 캐릭터 생성·선택, 능력치 투자, 경험치·레벨업, 처치 보상표, 전투 계산을 `DALMARU_DB_PASSWORD=postgres`로 실제 DB에서 확인했다. 캐릭터 저장소·선택 검증은 시작·종료 정리 순서를 프로필 외래 키에 맞춰 보완했다.
- 목록 자동 재시도: 빌드 및 localhost 재접속 통과. `Player768`이 수정된 클라이언트로 `localhost:25565`에 접속했고, 서버 로그에 계정·목록 요청 오류가 없었다. 게임 창 제어 표면 미노출로 화면 텍스트의 최종 시각 확인은 사용자가 보는 창에서만 가능하다.
- localhost 직접 연결 재시험: 통과. `DALMARU_DB_PASSWORD=postgres`를 가진 전용 서버에 `Player11`이 접속했고, 이전 계정 세션 DB 오류는 재발하지 않았다. 게임 창 자동 제어 표면이 없어 `J` 전직·퀘스트 버튼의 시각적 조작은 미확인이다.
- localhost 직접 연결: 부분 통과. 테스트 클라이언트 `Player911`이 `localhost:25565` 전용 서버에 실제로 접속했다. 서버는 계정 세션 준비 중 `DALMARU_DB_PASSWORD` 미설정으로 실패했으므로 캐릭터 선택·전직·퀘스트 UI 검증은 진행하지 못했다.
- 전직·퀘스트 통신 빌드: 통과. `server-project/gradlew.bat build --no-daemon`이 성공했다.
- 인게임 검증: 보류. 이미 실행 중인 프로세스가 `server-project/run` 월드를 잠가 전용 서버가 기동하지 못했고, 자동화 도구가 새 Minecraft 창을 탐지하지 못했다. 기존 Minecraft/서버 종료 또는 별도 실행 디렉터리가 필요하다.
- 전직 선택 통신: 통과. `server-project/gradlew.bat build --no-daemon`이 성공했다. 서버·클라이언트 전직 요청·응답과 프로토콜 8을 컴파일했으며, 인게임 조건별·재접속 수동 확인은 남았다.
- 문서 체계 검증: 통과. 공식 문서 ID 17개가 문서 관리 기준과 각 문서에 모두 존재하며, Markdown 상대 링크 대상이 모두 존재함을 확인했다.
- PLT-001 빌드: 통과. Temurin JDK 25.0.4.1, Minecraft 26.1.2, Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2, Loom 1.17.21, Gradle Wrapper 9.5.1에서 `server-project\\gradlew.bat build --no-daemon`이 성공했다.
- PLT-001 전용 서버 기동: 통과. EULA 동의 후 `runServer`가 달마루 0.1.0을 포함해 모드를 로드하고 25565 포트에서 월드를 생성한 뒤 `Done` 상태까지 도달했다. 클라이언트 전용 클래스 로딩 오류는 없었다.
- PLT-001 개발 클라이언트 기동: 통과. `runClient`가 달마루 0.1.0을 로드하고 렌더링·사운드 초기화까지 완료했다.
- PLT-001 DB 연결: 통과. PostgreSQL 18.6의 `ProjectD`에 pgJDBC 42.7.13으로 연결하고 `current_database()`와 서버 버전을 조회했다. 자격 증명은 환경 변수로만 전달했으며 저장하지 않았다.
- PLT-001 의존성 잠금: 통과. `server-project/gradle.lockfile`을 생성하고 잠긴 의존성 상태에서 빌드·DB 연결 검증을 재실행했다.
- Phase 02-1 스키마: 통과. `dalmaru.accounts`, `dalmaru.characters`, `dalmaru.schema_migrations`를 `ProjectD`에 적용했다. 캐릭터 계정·슬롯 유일성과 이름 정규화 유일성 제약 두 개를 확인했다.
- Phase 02-2 계정 저장소: 통과. 같은 UUID로 계정 세션을 두 번 열어 연결 세대가 1에서 2로 원자 증가함을 확인했다. 검증용 계정 행은 즉시 삭제했다.
- Phase 02-3 캐릭터 생성 저장소: 통과. 1번 슬롯의 캐릭터 생성과 5번 슬롯 거부를 실제 DB에서 확인했다. 외래 키 제약에 따라 검증용 캐릭터를 먼저 삭제한 뒤 계정을 삭제했다.
- Phase 02-4 선택·전환 저장소: 통과. 계정의 캐릭터 목록 조회와 소유 캐릭터만 활성화하는 원자 갱신을 실제 DB에서 확인했다. 활성 참조를 해제한 뒤 검증 행을 삭제했다.
- Phase 02-5 캐릭터 목록 통신·화면: 빌드·기동 통과. 서버가 호환성 확인과 계정 세션 완료 뒤에만 해당 계정의 캐릭터 목록을 비동기 조회하고, K 키 화면이 슬롯 1~4와 활성 상태를 표시하도록 컴파일했다. 이전 서버를 종료하고 새 전용 서버가 25565에서 기동했으며, 새 클라이언트도 렌더링·사운드 초기화까지 완료했다. 자동화 도구가 Minecraft 창을 인식하지 않아 접속·K 키 목록의 최종 통합 확인은 미실행이다.
- Phase 02-5 계정 초기화 오류 처리: 빌드·서버 재기동 통과. 접속 계정 세션 생성 예외가 상태를 삭제한 채 숨겨지던 경로를 수정해, 서버 로그와 목록 화면에 저장소 연결 실패를 표시하도록 했다. 기존 접속 `Player982`의 계정 행이 DB에 없음을 확인했으며, 새 접속의 오류 로그로 근본 원인은 아직 미확정이다.
- Phase 02-5 저장소 재검증: 통과. `verifyProjectD`, `verifyAccountRepository`, `verifyCharacterRepository`가 PostgreSQL 18.6에서 모두 성공했다. 검증이 만든 고정 테스트 계정·캐릭터 행은 활성 참조를 해제한 뒤 삭제했다.
- Phase 02-5 목록 통합: 통과. `Player279` 접속 뒤 계정 행과 연결 세대 1이 PostgreSQL에 생성됨을 확인했다. 이전의 `No suitable driver` 오류는 Fabric의 JDBC 자동 등록 문제였고, 명시적 드라이버 로드로 수정했다. 자동 목록 조회와 즉시 새로 고침의 1초 제한 충돌은 클라이언트 쿨다운과 마지막 성공 목록 보존으로 보완했으며 빌드가 통과했다.
- Phase 02-6 캐릭터 선택: 통과. V002 `character_selection_requests`를 실제 DB에 적용했다. 같은 요청 ID·같은 캐릭터는 이전 결과 반환, 같은 ID·다른 캐릭터와 이전 연결 세대는 거부, 활성 캐릭터 갱신 유지까지 `verifyCharacterSelection`으로 확인했다. 검증용 계정·캐릭터·요청 행은 자동 정리됐고, 스키마 버전 1·2와 테스트 계정 0건을 확인했다.
- Phase 02-7 캐릭터 생성 화면·통신: 자동 검증 통과. V003 `character_creation_requests`를 실제 DB에 적용했고, 이름 입력란과 빈 슬롯 생성 버튼·서버 생성 요청·성공 목록 반영을 컴파일했다. `verifyCharacterCreation`으로 최초 생성, 같은 요청의 원래 결과 반환, 같은 ID의 다른 요청 거부, 이전 접속 세대 거부를 실제 DB에서 확인했으며 검증 행은 자동 삭제됐다. 새 전용 서버도 25565에서 기동했다.
- Phase 02-7 생성 화면 배치: 통과. 이름 입력·생성하기·슬롯 1~4·새로 고침 순서로 재배치했다. 첫 컴파일은 람다 캡처 오류로 실패했으나 즉시 수정했고, 최종 `build`가 성공했다.
- Phase 02-8 캐릭터 시작 프로필: 통과. V004 `character_profiles`를 실제 DB에 적용해 기존 캐릭터 프로필도 채웠다. 새 캐릭터의 레벨 1, 경험치·미배분점·다섯 직접 능력치 0, 무료 초기화 가능 상태를 `verifyCharacterCreation`으로 확인했고 빌드가 성공했다.
- Phase 02-9 접속 캐릭터 선택 순서: 빌드 통과. 통신 버전을 3으로 올리고 계정 세션·호환성 확인 뒤에만 선택 시작 신호를 보내도록 했다. 접속마다 선택·생성 화면을 열고 선택 응답 전에는 닫아도 다시 열며, 기존 활성 캐릭터도 선택할 수 있다. 실제 접속 화면 확인은 새 클라이언트·서버 재기동 후 필요하다.
- Phase 02-10 활성 캐릭터 프로필 조회·표시: 빌드 통과. 통신 버전을 4로 올리고 선택 성공 뒤 서버가 저장된 프로필만 조회해 전달하도록 했다. `P` 키 화면에 레벨·경험치·미배분점·다섯 직접 능력치·무료 초기화 가능 여부를 표시한다. 새 클라이언트·서버 재기동 후 실제 화면 확인이 필요하다.
- Phase 02-10 입력·재요청 보정: 빌드 통과. `P`가 바닐라 사회적 상호작용 화면을 여는 충돌을 확인해 프로필 키를 `J`로 변경했다. 목록 요청의 클라이언트 대기 시간을 25틱으로 조정해 1초 서버 제한 직전의 재요청을 막았다.
- Phase 02-10 프로필 단축키 보완: 빌드 통과. 실제 접속 로그에서 캐릭터 선택 뒤 `character_profile_response` 수신을 확인했다. `J` 키를 마인크래프트 키 바인딩 처리와 분리해 인게임 실제 키 입력으로 직접 감지하며, `K` 화면 우회 버튼은 제거했다.
- Phase 02-11 능력치 투자·캐릭터 전환: 통과. V005 `character_stat_investment_requests`를 실제 DB에 적용했다. `verifyCharacterStatInvestment`로 활성 캐릭터만 투자, 최초 투자, 동일 요청의 저장된 결과 반환, 요청 본문 변경 거부, 미배분점 초과 거부를 실제 PostgreSQL에서 확인했고 검증 행을 삭제했다. 클라이언트는 `J` 프로필 화면에서 미배분점이 있을 때 다섯 능력치 `+1`을 요청하며, 인게임의 다른 캐릭터 선택 성공 뒤 재접속을 요구한다. 새 클라이언트·서버 재기동 후 수동 확인이 필요하다.
- Phase 02-12 무료 초기화: 빌드 통과. V006 `character_stat_reset_requests`를 실제 DB에 적용했다. 활성 캐릭터의 직접 투자분만 반환하고 최초 1회 상태를 소진하는 서버 요청·응답과 `J` 화면의 무료 초기화 버튼을 구현했다. 실제 DB 단위 검증과 수동 화면 확인은 다음 단계다.
- Phase 02-13 경험치·레벨업 계산: 통과. 서버 저장소에 1~59레벨의 필요 경험치 `100 + 35 × (현재 레벨 - 1)`, 60레벨 상한, 레벨당 미배분점 5점 지급을 추가했다. `verifyCharacterExperience`가 실제 PostgreSQL에서 경험치 100으로 레벨 2·미배분점 5, 추가 경험치 170으로 레벨 3·경험치 이월 35·미배분점 10을 확인하고 검증 행을 삭제했다.
- Phase 02-13 경험치 흐름: 통과. 운영자용 `/dalmaru xp <수치>`가 현재 선택 캐릭터에만 경험치를 지급하고 갱신 프로필을 즉시 전달하도록 연결했다. 새 전용 서버를 25565 포트에서 재기동했고, `build verifyCharacterExperience`가 성공했다. 인게임 운영자 명령의 최종 수동 확인만 남았다.
- Phase 03-1 처치 경험치: 통과. 서버의 처치 확정 이벤트에 보상 표를 연결했다. 좀비·허스크·드라운드·거미·동굴거미는 25, 스켈레톤·스트레이·보그드는 30, 크리퍼는 40, 마녀·엔더맨은 60 경험치를 지급하며 비전투 동물은 0이다. `build verifyCharacterExperience verifyCombatRewards`가 통과했고, 새 전용 서버가 25565에서 기동했다. 인게임 처치의 최종 수동 확인만 남았다.
- Phase 03-2 전투 계산: 통과. 서버 공통 계산이 생명력 `200 + (레벨-1)×20 + 체력×10`, 기력 `100 + 정신`, 회복 `5 + 정신×0.02`, 주력 계수 `1 + 주력×0.004`와 피해·방어·치명타 공식을 구현했다. `build verifyCombatCalculator verifyCombatRewards`가 통과해 500 방어의 50% 감소, 75% 상한, 치명타 150%를 확인했다.
- Phase 03-3 전투 상태·퀘스트 저장 기반: 통과. 실제 적용 피해 뒤 전투 상태를 갱신하고 10초 뒤 기력을 회복하는 서버 런타임을 컴파일했다. V007 `character_quest_progress`를 PostgreSQL에 적용해 테이블·인덱스 생성을 확인했으며, 최종 `build`가 성공했다.
- Phase 03-4 퀘스트 저장소: 진행 중. `QuestRepository`의 수락·처치 진행·완료 보상 트랜잭션을 추가했고 `compileJava`가 통과했다. 서버 명령·처치 이벤트·통합 DB 검증은 아직 연결 전이다.
- 개발 접속 설정: 통과. 개발 클라이언트의 Mojang 테스트 세션 401과 `online-mode=true`의 조합으로 인증이 거부됨을 확인했다. 개발 전용 `run/server.properties`를 `online-mode=false`로 변경하고, 서버가 오프라인 개발 모드 경고와 함께 25565에서 기동함을 확인했다. 배포 서버에서는 반드시 `true`를 사용한다.
- PLT-002 정상 연결: 통과. 개발 클라이언트 `Player214`가 `localhost`에 로그인한 뒤, 서버의 10초 Hello 제한이 지난 뒤에도 연결이 유지됐다. 사용자가 O 키로 안내 화면을 열고 연결 확인을 실행했다.
- PLT-002 버전 불일치: 통과. 임시 통신 버전 2 서버가 프로토콜 1 클라이언트를 `달마루 통신 버전이 맞지 않습니다.`로 거부했다. 시험 직후 서버 버전은 1로 복구했다.
- PLT-002 클라이언트 시간 초과: 통과. ProbeResponse를 임시 생략했을 때 안내 화면이 5초 뒤 `서버 응답 시간이 초과되었습니다.`를 표시하고 재시도 버튼을 활성화했다. 오류 문구가 버튼 갱신에 덮이는 문제를 수정한 뒤 확인했다.
- PLT-002 서버 시간 초과: 통과. Hello를 임시 생략한 클라이언트가 접속 10초 뒤 `달마루 호환성 확인 시간이 초과되었습니다.`로 연결 거부됐다. 시험 직후 Hello 전송을 복구했다.
- PLT-002 완료 조건: 통과. 정상 연결·불일치 거부·서버와 클라이언트 시간 초과·재시도·O 키 안내 화면을 실제 확인했다.
- 미실행: 잘못된 Probe 요청, 요청 제한, 두 사용자·재접속 독립성, PostgreSQL 연결.
- 다음 조치: 새 빌드로 클라이언트·전용 서버를 재기동해 K 키 목록 화면을 확인하고, 캐릭터 선택 요청의 멱등성 저장을 구현한다.
2026-09-26 02:40 +09:00 — `compileJava compileClientJava --no-daemon` 통과. 주력 능력치 공격 피해 수정자와 프로필 갱신 경로가 컴파일되며, 기력 상태 패킷 추가에 맞춰 프로토콜 버전을 10으로 갱신했다.
2026-09-26 02:42 +09:00 — `verifyCombatCalculator --no-daemon` 통과: 서버 전투 공식 검증 완료.
2026-09-26 03:00 +09:00 — `compileJava compileClientJava verifyCombatCalculator --no-daemon` 통과: 최대 생명력 속성 연동 및 전투 계산 검증 완료.
2026-09-26 03:10 +09:00 — `compileJava compileClientJava verifyCombatCalculator --no-daemon` 통과: 피격 전투 상태와 초기 전투 상태 판정 변경 컴파일 완료.
2026-09-26 03:25 +09:00 — localhost 재시험 미통과: OneDrive 밖 임시 복사본에서도 개발 서버 프로세스가 포트 25565를 유지하지 못했다. UI 자동화 표면도 노출되지 않아 직접 연결 검증은 미완료다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 전사 횡참 요청·서버 기력/재사용 검증·범위 피해 컴파일 완료.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 전투 프로필 캐시 갱신 및 횡참 서버 판정 컴파일 완료.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 횡참 기력·재사용 실패 피드백 컴파일 완료.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 횡참 애니메이션·파티클 컴파일 완료.
2026-09-27 — `compileJava --no-daemon` 통과: 횡참 전방 범위 판정 컴파일 완료.
2026-09-27 — `compileJava compileClientJava testClasses --no-daemon` 통과: V009 기술점 영속화, 최초 전직 6점 지급, 프로필 패킷·화면 표시를 컴파일했다. `verifyCharacterProfession`은 V009를 PostgreSQL에 적용하고 DB 자격 증명을 설정한 뒤 실행 예정이다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: V010 전사 공통 기술 습득 저장소와 요청·응답 통신을 컴파일했다. V009·V010 적용 뒤 실제 DB 멱등성 검증과 기술 화면 연결이 남았다.
2026-09-27 — `applyCurrentMigrations verifyCharacterProfession verifyCharacterSkill --no-daemon` 통과: V009·V010을 개발 PostgreSQL에 적용했고, 최초 전직 기술점 6점 지급과 같은 기술 습득 요청의 재실행 시 중복 차감 없음을 확인했다. `compileJava compileClientJava testClasses --no-daemon`도 통과했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 전사 진격 `V` 요청, 서버 대시·전방 타격, 랭크 피해와 파티클·사운드 연출을 컴파일했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 전사 승룡격 `B` 요청, 서버 상승 타격, 랭크 피해·상승 효과와 파티클·사운드 연출을 컴파일했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 전사 기합 `N` 요청, 서버 둔화 해제·랭크별 저항 효과와 파티클·사운드 연출을 컴파일했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 궁수 관통 사격 `G` 요청, 서버 직선 타격, 랭크 피해와 화살 파티클·발사음 연출을 컴파일했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 법사 영부탄 `C` 요청, 서버 직선 타격, 랭크 피해와 부적 파티클·주문 시전음 연출을 컴파일했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 도적 급습 `X` 요청, 서버 돌진·측후방 보너스 타격, 연막 파티클·치명타음 연출을 컴파일했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 궁수 후퇴 사격 `Z` 요청, 서버 후방 이동·전방 타격, 구름 파티클·발사음 연출을 컴파일했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 법사 속박부 `H` 요청, 서버 둔화 제어·랭크 피해, 부적 파티클·주문 시전음 연출을 컴파일했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 도적 연막탄 `Y` 요청, 서버 범위 실명 제어, 대형 연막 파티클·소화음 연출을 컴파일했다.
2026-09-27 — `compileClientJava --no-daemon` 통과: 기술점 소진 뒤에도 `J` 기술 랭크 화면을 열 수 있게 컴파일했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 도적 족쇄격 `M` 요청, 서버 둔화 제어·랭크 피해, 족쇄 파티클·사슬음 연출을 컴파일했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 도적 긴급 이탈 `I` 요청, 서버 후방 이동·둔화 해제, 구름 파티클·순간이동음 연출을 컴파일했다.
2026-09-27 — `compileJava compileClientJava testClasses --no-daemon` 통과: 현재 4단계 공통 액티브·연출 변경 전체를 서버·클라이언트·검증 클래스까지 재확인했다.
2026-09-27 — `compileJava compileClientJava --no-daemon` 통과: 궁수 추적 표식 `P` 요청, 서버 기력·재사용·대상 검증과 랭크별 글로우·파티클·사운드 연출을 컴파일했다.
2026-09-27 — `compileJava compileClientJava testClasses --no-daemon` 통과: 궁수 속박 사격과 법사 호신부·축지까지 포함한 4단계 공통 액티브 서버·클라이언트·검증 클래스를 컴파일했다.
2026-09-27 `applyCurrentMigrations verifyCharacterEquipment --no-daemon` 통과: V011 적용과 9칸 귀속 장비 생성, 소유권 거부, 강화 요청 재전송 멱등성, +15 상한을 PostgreSQL에서 검증했다.
2026-09-27 `compileJava compileClientJava verifyEnhancementRules --no-daemon` 통과: 장비 목록·강화 클라이언트 화면과 설계표 강화 확률(+1 100%~+15 5%)을 컴파일·검증했다.
2026-09-27 `applyCurrentMigrations verifyCharacterRun --no-daemon` 통과: V012 적용 뒤 초행 예약·동일 요청 재전송·초행 성공 후 수련 해금·수련 실패 뒤 재입장을 PostgreSQL에서 검증했다.
2026-09-27 `compileJava compileClientJava testClasses verifyCharacterRun --no-daemon` 통과: F6 공략 난이도 화면과 서버 예약 통신을 컴파일하고 공략 예약 통합 검증을 재실행했다.
2026-09-27 7단계 입장 런타임: `compileJava --no-daemon` 통과. 공략 ID별 분리 좌표 발판 생성과 서버 텔레포트 경로를 컴파일했다.
2026-09-27 7단계 부활 런타임: `compileJava compileClientJava --no-daemon` 통과. 공략 ID 유지, 난이도별 부활 횟수와 재생성 뒤 공략 발판 복귀·생명력 35% 경로를 컴파일했다.
2026-09-27 7단계 런타임·복원: `compileJava compileClientJava testClasses verifyCharacterRun --no-daemon` 통과. ACTIVE 공략 조회/복원과 기존 예약·성공·실패 DB 통합 검증을 통과했다.
2026-09-27 8단계 공략 진행: `compileJava compileClientJava --no-daemon` 통과. 서버 보스 생성·처치 기반 단계 전환·최종 클리어 DB 확정/경험치 보상 경로를 컴파일했다. 실제 인게임 보스/화로 상호작용 시험은 다음 검증에서 수행한다.
2026-09-27 8단계 완료 검증: `compileJava compileClientJava testClasses verifyRunEncounterRules verifyCharacterRun --no-daemon` 통과. 1/2/3/4인 화로 동시 정화 규칙, 수련 보스 체력, 공략 예약·성공/실패 PostgreSQL 경로를 검증했다.
2026-09-27 9단계 파티: `compileJava compileClientJava testClasses verifyPartyRuntime --no-daemon` 통과. 초대·수락·이탈에 따른 1~4인 파티 런타임과 파티 공략 입장 경로를 컴파일·검증했다.
2026-09-27 `applyCurrentMigrations compileJava compileClientJava testClasses verifyRunRewards verifyCharacterRun --no-daemon` 통과. PostgreSQL에서 V013/V014를 적용하고, 초행 70전·연철석 3·봉수패 1·15레벨 상자, 수련 100전·연철석 5·봉수패 2·20레벨 상자, 보상 재전송 시 원장·잔액 중복 없음과 기존 공략 예약을 확인했습니다.
2026-09-27 `verifyRunLifecycle --no-daemon` 통과. PostgreSQL에서 무참가 공략의 PAUSED 전환, 재개, 만료 후 FAILED 전환을 확인했습니다. `compileJava compileClientJava testClasses`도 통과했습니다.
2026-09-27 `applyCurrentMigrations compileJava compileClientJava testClasses verifyEnhancementRules verifyCharacterEquipment verifyCharacterRun verifyRunEncounterRules verifyPartyRuntime verifyRunRewards verifyRunLifecycle --no-daemon` 통과. 보상·수명주기 검증은 출력 확인을 위해 별도로 재실행해 모두 성공했습니다. 실제 Minecraft 조작(파티 재접속·화로·보관함 수령)은 자동화 표면이 없어 미실행입니다.
2026-10-08 MDF-3 (Gradle 미사용 java 직접 실행, DALMARU_DB_PASSWORD 설정): RunEncounterRulesCheck·RunLifecycleCheck·RunRewardCheck 모두 exit 0 통과. 실제 인게임 F6 플레이 루프는 클라이언트 조작 수단이 없어 미실행. 증거: verification-mdf3/.

2026-10-08 MDF-3 추가 확인(worker-oscar-main): 이번 실제 실행은 PostgreSQL select 1(exit 0, stdout 1, stderr 없음)과 Java 프로세스 조회(0개)입니다. RunEncounterRulesCheck/RunLifecycleCheck/RunRewardCheck는 기존 exit 0 로그를 재사용했으며 재실행하지 않았습니다. 실제 F6·보스·보상·주간 횟수 플레이 검증은 미실행. 근거: verification-mdf3/OSCAR_CURRENT_REVIEW.md, oscar-current-db-check.json.


2026-10-08 F6 자동 플레이 검증 — BLOCKED (2026-10-08T08:40:33.599Z). npm 패키지 조회 exit 1(EACCES), 오프라인 Gradle exit 1(Loom 미발견), javap exit 4(일부 JAR 접근 거부). 직접 Fabric 서버는 LoaderUtil.normalizeExistingPath의 AccessDeniedException을 기록했고 25565는 ECONNREFUSED였습니다. launcher exit는 미확보, 자체 launcher 정리/조회는 접근 거부(exit 1). 실제 F6·맵·전투·보상·주간 횟수는 미실행, 기존 DB PASS 3개는 재실행하지 않았습니다. 원본 server.properties SHA-256 전후 일치. 로그·한계: verification-mdf3/F6_AUTOPLAY.md, autoplay-environment.json, F6-server.*.
2026-10-08 F6 실서버 자동 플레이(runServer + mineflayer 봇): compileJava 통과. 실패 경로 PASS(6번째 사망 → run FAILED, clear_count 0, 보상 0건), 성공 경로 PASS(COMPLETED, clear_count 1, 보상 1건, 경험치 500), 부활 체력 35% PASS. 보스 처치 2회와 레벨 설정은 서버측 검증, TRAINING 입장은 미실행. 증거: verification-mdf3/F6_AUTOPLAY.md, tools/autoplay/logs/.


2026-10-08 MDF-5: 무시 규칙 회귀 확인 PASS, `node --check server-run.js`와 `server-run.test.js` exit 0. 실제 TRAINING 입장 PASS; 부활3회·실제4번째 사망 FAILED·주간0/보상0 PASS; 완료 DB COMPLETED·주간1·100전/연철석5/봉수패2/20레벨상자1 PASS. 맵·화로·보상 구역은 봇 실제 이동, 보스 처치는 콘솔 피해 명령을 사용한 서버측 검증. 서버/봇 exit0, 자체 데몬 종료·Java0. 서버 속성 값 전후 동일(엔진 주석 타임스탬프 갱신). 과거 DB 단위 검증은 반복하지 않았습니다. 증거: verification-mdf3/MDF5.md, mdf5/checks.json 및 실행/DB 로그.

- 2026-10-08 MDF-5 병합 후: main HEAD 2ca4aa9bc62af65bdf6d2a8c2dff15c7b5695bd2에서 node tools/autoplay/server-run.test.js 1회 실행, exit 0 / 4사례 PASS. 실제 서버 미기동, Java 프로세스 0.

- 2026-10-08 작업 트리 정리: Git으로만 확인했습니다. MDF-5 무시 규칙/안내 유지, 새 소스 커밋의 실행 로그 0개 확인. 일반 diff --check는 기존 server-project/.gitattributes의 EOF 빈줄 1건을 경고했고, 해당 기존 형식만 제외한 검사는 통과했습니다. 빌드·게임 시험은 실행하지 않았습니다. 이 파일은 기존 자격증명 기록 때문에 커밋에서 제외합니다.

- 2026-10-08 MDF-8 진단: Java25 새 임시 클래스 컴파일 후 CombatCalculatorCheck PASS(exit0), 15레벨 설계 수치 assertions PASS(exit0): HP740, 피해52, 보스208/277타, 피격96. 임시 진단 소스의 BOM 오류는 수정 후 통과했습니다. 서버/봇/수동 플레이/완주 시험은 미실행, Java 잔존0. 설계 선택 대기이며 제품 코드 변경 없음. verification-mdf3/MDF8.md.

- 2026-10-08 MDF-8 A안 기반: 새 Java 컴파일 후 SoloCombatDefinitionCheck PASS(exit0). 실제 장비 소유권/슬롯 중복/레벨 조건, 권장 장비 공격51·물리방어75·술법방어38·추가능력치8/6/4, HP740·피해52·208/277타, RPG 체력/피해 왕복 확인. 서버 미기동, 엔진 전투 연결은 아직 미검증.

- 2026-10-08 MDF-8 A안 완료: 실제 장착 장비·RPG 체력/피해·기본 공격/기술/보스 경로 연결. 컴파일·장비/계산 assertions PASS, 서버측 체력 경계 540/720/993.6 및 피해 2.6 PASS. 서버 exit 0·Java 0·설정값 보존 확인. 실제 완주·다인·회복/보호막 미검증, Jim QA·Creed 리뷰 대기. 상세: verification-mdf3/MDF8.md.

- 2026-10-08 MDF-8 보안 후속: tools/autoplay/server-run.js의 DB 비밀번호 폴백 제거. DALMARU_DB_PASSWORD 미설정/빈 값이면 로그 생성·서버 실행 전에 명확한 오류로 종료. node tools/autoplay/server-run.test.js PASS(exit 0): 기존 JAR 차단 및 환경변수 누락/빈 값 검증, 실제 서버 미기동. 이력 재작성·push 없음; 과거 이력 처리는 사람 결정 대기.

- 2026-10-13 MDF16 1단계: compileJava compileClientJava verifyCombatConsistency PASS(exit0/13초). 실제제품 tick/useSweep/recordValidHit를 대역 실행해 전투중회복·offline동결·clamp·허공타이머·반복비용 검사, 횡참5랭크·필드보상유지/던전태그보상차단 검사. 실제 mixin loot 취소/실전 GUI·몹은 후속헤드리스검증 예정.

- 2026-10-13 MDF16 체크포인트: compileJava compileClientJava verifyCombatConsistency 최종PASS(exit0/9초), 모델효과/충전/방어/면역 및 실제제품기력/허공메서드대역 검사. verifyLoadoutDatabase PASS(exit0/9초, 임시PG57700) owner/세대/revision/replay/kind/ACTIVE/reconnect/history. V023 임시만/V024 미적용. 전체회귀·실제mixin/headless·몹spawn·C05/6 안전위치/DB/GUI 미검증. 임시PG 정상종료/운영PID4936 미조작. 상세 공유 hive/research/MDF16_J02_IMPLEMENTATION.md. PARTIAL.
- 2026-10-13 최종 MDF16/J02: verifyBeaconTowerMobs PASS(4초): 실제 정의 6종/24마리 roster/인원배율. verifySafetyDatabase PASS(3초): V024 임시PG57700 persistence/owner/stale-generation. verifyReadyBoundary PASS(실제 READY 메서드): pending-write/전원준비/세션·roster·town·disconnect, 후속 준비UUID 불일치 차단 검사 PASS.
- 전체회귀 1회(build + 18 verify,19초): 6개 기존 source-extraction fixture가 새 필드·메서드/문기대값 불일치로 FAIL. 시험대역 갱신 후 해당6개(EquipmentRespawn/BeaconTowerTerrain/ManualPlayRegression/PremergeRuntime/ReviewRegression/SessionLifecycle)만 재실행 PASS(10초). 나머지 verifyEnhancementRules/RunEncounterRules/PartyRuntime/CombatRewards/CombatCalculator/SoloCombatDefinitions/SelectionBoundary/CombatConsistency/LoadoutDatabase/PremergeDatabase/ReviewDatabase/LifecycleDatabase PASS. 전체성공으로 원래 실패 실행을 덮어쓰지 않음.
- 격리 Minecraft57702 + 임시DB57700 헤드리스: 첫 부팅은 성공이나 별도 runtime 시험구성이 global entity hook에서 discard되어 FAIL. 실제 global runtime으로 시험구성 수정 후 재검증 PASS(16초): 실제 G0/G1→첫보스/G2/G3 roster, elite tag, 지휘자 HP50% 소환2/재소환0, 종료 개체·registry·지형복구, 문을 넘지 않는 안전바닥. common mixin 부팅도 확인. 실제 피해·loot·XP 취소/화로4초 상호작용/부활resource·타이밍/클라이언트mixin·GUI·실제packet은 검증범위 밖.
- 최종 READY 취소 UI/준비UUID packet 변경 후 verifyReadyBoundary + verifyManualPlayRegression + compileClientJava PASS, assemble PASS. git diff --check PASS. 운영JAR SHA256 1B180A3C90FEF48521ADD06EB086AC2DF38E6A5D2BCC541BB74A5D6F298E6539 불변. 임시PG data_directory 소유경로 확인 후 정상종료.

- 2026-10-13 후속QA: verifyClientScreenBounds/verifyFieldReviveBoundary/verifyReadyBoundary/verifyReviewRegression/verifySessionLifecycle/compileJava/compileClientJava/assemble PASS. 실제4인protocol28/READYUUID/장착초기화/ACTIVE/frozen/ACTIVEedit거부/slot중복비용 PASS. 실제client렌더+주입키handle경로/Grave·Alt기본값/문자키제거 PASS(상태snapshot,물리키미시험). 실제fieldcheckpoint15.5,101,0.5/2900ms/HP12of24/다음HUD기력55of100/생활복귀 PASS. 초기fixture실패·실제checkpoint덮어쓰기결함원인수정경위는verification-mdf16/QA.md 참조. 운영JARhash불변/운영DB미접근/추가배포없음.

- 2026-10-13 fullrun.js 첫실행PASS(격리server57702/PG57700): run d321eef2-ff44-4bb3-a794-a2331d437752 COMPLETED4/4,requiredKills26+정책fixtureZombie1. guaranteedDiamond loot0/orb0/필드XP·quest0, 일반몹grant0. 실제부활/피해차단·화로80tick/2인·소환1회 PASS. 개인cleargrant70/3/1/상자15,ledger4,주간clear1,return_pending0,cleanup개체0/지형복원true. 기존bossXP/quest는리더50/2 유지,최종XP리더550·타인500. 실제키·보스인원배율/수동밸런스미검증. 제품추가수정없으므로성공전체회귀/빌드반복없음. 상세 verification-mdf16/FULLRUN.md.


## 2026-10-13 MDF16 ?? ?? ?? ??

- PASS: `compileJava compileClientJava` (?? ?? ???).
- PASS: `verifyReviewFixBoundary` (R1-R5 production ?? guard ??). ? ??? R1 ?? ???? ?? ???? ?? ??? assertion? ?? ? ??????.
- ??: ?? ??? ?? ???? ?? ????/? AI? ???? ????. R6-R9? ?? ?????.


## 2026-10-13 ??? N1

- PASS: `verifyRunEntryCancellation` (cancel?disconnect, disconnect?cancel, stale generation ???). ?? DB `oscar_fixture`/57700/V024?? ?? ? ?? ?? PostgreSQL? ?? ??????.
- PASS: `git diff --check`.

- N1 ??: `verifyRunEntryCancellation` ??? PASS. cancel ?? ??? ??? ? ?? ??? ???? ??. ?? ??? ?? ??? ??(CharacterRecord ??)? ?? ? ??????.

- Review3 ?? N1/N2: `verifyRunEntryCancellation` PASS (cancel?disconnect, disconnect?cancel, reconnect ? ?? ?? ??, ??? generation tuple ??). N2 cleanup? production `.whenComplete` ?? ???? ?? ?? spawn ?? ?? ??? ???? ????.

## 2026-10-13 MDF16 Review4 N1-N3
- PASS: `verifyRunLifecycleOwnership`
- PASS: `compileJava compileClientJava`
- PASS: `git diff --check`
- ACTIVE replay는 owner tuple과 현재 파티 전체의 runtime participant 일치를 확인한 뒤 응답한다. replay 거절은 활성 런 정리(close) 경로로 보내지 않는다.
- 제한: 실서버 다중 접속/경합 QA 미실행. 운영 DB·서버 접속, 배포 없음.

## 2026-10-13 MDF16 Review5 N4/N5
- PASS: `verifyRunLifecycleOwnership`
- PASS: `compileJava compileClientJava`
- PASS: `git diff --check`
- JOIN restore catch no longer closes the shared run; it rejects only the failing connection.
- Runtime shutdown cleanup continues for each run and DB cancellation is attempted independently.
- No live server/DB test or deployment; multi-client and shutdown-failure QA remains outstanding.

## 2026-10-14 MDF-P1 S01/R10
- PASS: --no-daemon verifyCombatConsistency compileClientJava (17s; compileJava/testClasses executed).
- R10 cases: 200-tick manual entry, decay, expiry/cancel, automatic/manual overlap, fixed-mode suppression, expired long-tick deadline.
- PASS: git diff --check. No live server/DB access, dungeon entry, M8Oscar use, or migration. No GUI/manual play verification; independent QA pending. Single-use Gradle daemon exits after build.

## 2026-10-14 MDF-P1 S02/R6
- PASS: --no-daemon verifyCombatConsistency compileClientJava (11s; compileJava/testClasses included).
- Hostile classification covers monster, targeted neutral, run-tagged entity and denied domain/passive/environment policy.
- Actual protect method extracted into a fixture with real DefenseState/CombatEffects: valid/invalid full guard, dodge/passive, shield absorption, zero damage (8 cases). Existing combat and S01 checks preserved.
- PASS: git diff --check. No live damage/event invocation, server/DB use, M8Oscar or dungeon entry; independent QA pending. No migration.

## 2026-10-14 MDF-P1 S03/R7
- Initial verifyFieldReviveBoundary FAIL: existing fixture lacked activeCheckpoint and startPending/startInvalidated from the baseline. An attempted fixture edit used the wrong working-directory path; the unchanged fixture failed again.
- Updated fixture to execute the actual ownership helper and verify preparation invalidation/foreign-character checkpoint rejection.
- PASS: --no-daemon verifyFieldReviveBoundary compileClientJava (8s); production compileJava passed before fixture correction.
- Actual selector body tested for active priority, same-dimension priority/unsafe first town, missing dimension, malformed dimension, world entry, all-invalid rejection, absent point/towns (7 paths), plus existing snapshot/wait/deadline/selection/disconnect checks.
- PASS: git diff --check. No live floor/teleport, server/DB, dungeon entry, M8Oscar, or migration; independent QA pending.

## 2026-10-14 MDF-P1 S07/J15
- PASS: --no-daemon verifyRunEncounterRules compileClientJava (9s; compileJava/testClasses included).
- 10 Instant-based KST cases: Wed late, Thu midnight, immediately before06:00, exact06:00, after06:00, next week, and year rollover; existing brazier/boss rules preserved.
- Reservation calls the same pure week rule; completion still uses its stored run week, with no data rewrite.
- PASS: git diff --check. No migration/server/DB/M8Oscar/dungeon use. Independent QA pending.
- 2026-10-14 MDF-P1 S05a: PASS `--no-daemon verifySkillPresentation compileClientJava` (10s, server/client compilation included). All24 descriptions match pilot specification; actual screen tick fixture checks max-rank disable, rank4 re-enable, rank0 learning and tooltip presence. No DB/live/human-play check.
- 2026-10-14 MDF-P1 S05b: PASS `--no-daemon verifySkillPresentation verifyClientScreenBounds compileClientJava` (11s). Four professions canonical defaults/no grants; retain/remove/move/new equip/duplicate validation; actual tick unlearned lock, rank1 unlock, ultimate lock, existing empty rows; 320x240 to960x540 layout bounds. No DB/live/human HUD play.
- 2026-10-14 MDF-P1 S04a: initial compile FAIL because support edit ran with wrong cwd, leaving deathTick/policy absent; corrected, then PASS `--no-daemon verifyRunRespawnBoundary verifyRunEncounterRules compileClientJava` (6s). Actual gate/auto/afterRespawn fixture:99/100/299/300ticks, auto once, HP35, single charge, disconnected/failed/zero/missing run, spectator. Vanilla bytecode confirms hook after thread handoff. No running-server Mixin or human death-screen check.
- 2026-10-14 MDF-P1 S04b: PASS `--no-daemon verifyPremergeRuntime verifyRunRespawnBoundary verifyRunDeathScreen compileClientJava` (10s). Actual server status100/300/expiry/failed/cleared; actual UI5s/1s/unlock/remaining/auto/spectator/field no-op/bounds320x240~960x540; existing ownership/death/reconnect/movement/combat regressions. Premerge initially FAIL from stale fixture keepRegionLoaded/getUUID/setPos/group; corrected. No deployed JAR Mixin, disconnected/fixture connection auto-call, or human field/dungeon checks; listed in hive research/MDF_P1_INTEGRATION_CHECKLIST.md.
- 2026-10-14 MDF-P1 S06a: PASS `--no-daemon verifyClientReplyRecovery compileClientJava` (10s). Actual ClientLoadoutState/ClientSkillState classes: initial query loss, equal-revision reverse order, unrelated query while edit pending, timeout recovery, foreign-character ignore, lower-revision ignore, late higher revision, selection request invalidation, rank latest-only and100tick retry. No sleep/DB/live/UI network-loss play; mutation requests are not retransmitted.
- 2026-10-14 MDF-P1 S06b: PASS `--no-daemon verifyClientReplyRecovery verifySkillPresentation compileClientJava` (10s). Actual ClientProfileState:99/100tick skill retry, repeated exact same object/ID/body, wrong-ID ignore, matching completion, selection cancellation/foreign-profile rejection, reset character/ID retry, profession exact retry/completion, no-point learning. Actual UI pending/no-point disables. Server DB replay/live packet-loss/human UI check not run; server durable contracts handled separately in M09.
- 2026-10-14 MDF-P1 S06c: PASS `--no-daemon verifyClientScreenBounds compileClientJava` (7s), `verifyRunDeathScreen compileClientJava` (8s after180px death layout update). Actual init/rebuild/statButton methods: logical320x180/320x240/427x240/640x360/960x540 bounds/overlap; equipment0/1/5/6/30 items, all pages/action IDs, resize and empty/shrink controls. Actual death UI also passes180px. First fixture FAIL from missing DalmaruSkillScreen navigation stub, corrected. Human client rendering/resize remains unchecked; support below320x180 is not claimed.
- 2026-10-14 MDF-P1 S09a: PASS `--no-daemon verifyEnhancementReplay verifyClientReplyRecovery compileClientJava` (9s). New isolated oscar_p1_fixture initialized from existing V001-V024 only. Two actual enhancements then old request: original outcome/latest equipment/no re-charge/no duplicate journal/body reuse rejection. Actual client inventory reply ordering, old acknowledgment no rollback,2s notice retention/expiry and selection invalidation. First DB assertion phase passed but cleanup FAIL (noncascade FK); cleanup transaction corrected and previous fixture account removed; final accounts0. Production DB/live unchanged; human replay UI unchecked.
- 2026-10-14 MDF-P1 S09b: PASS `--no-daemon verifyRequestJournal verifyEnhancementReplay compileClientJava` (10s, dedicated oscar_p1_fixture schema25). Canonical object order/numbers/body immutability/depth guard; UUID replay with changed correlation; changed body/owner/character/stale generation reject; domain+journal rollback; two concurrent UUID enhancements charge once; immutable result/event; reconnect-owned application ACK and idempotent applied_at; legacy original result/latest state tests. V025 initial apply and rerun PASS (retry first used wrong relative path, no DB change; corrected absolute path). Final fixture accounts0/requests0. Production DB/live, client UUID wire, actual world application ACK and old deployment JAR rollback not tested.
- 2026-10-14 MDF-P1 S09c: PASS `--no-daemon verifyClientRequestEnvelope verifyOperationWire verifyRequestJournal verifyEnhancementReplay compileClientJava` (14s, oscar_p1_fixture). Actual client context/generation/UUID/correlation/100tick same-object retry/terminal and accepted-state receipts/selection reset; actual Minecraft packet codecs preserve UUID/long generation/JSON/state/replay and reject8193-char body; definite validation/alias vs uncertain commit/timeout classification. Existing concurrent DB journal/legacy replay gates PASS. No running server/client exchange, world ACK or human recovery play; protocol30 is development only.
- 2026-10-14 MDF-P1 S09b codex minor1: RED `verifyRequestJournalResult` (8s): different large integer outcomes accepted. After exact canonical JSON comparison, PASS `--no-daemon verifyRequestJournalResult verifyRequestJournal verifyEnhancementReplay compileClientJava` (13s). New one regression compares9223372036854775806 vs9223372036854775807; original accepted, changed rejected, SQL forbidden by proxy. Existing fixture journal/rollback/concurrent/legacy gates PASS; no migration/protocol/live change.

## 2026-10-14 MDF-P1 S10a
- PASS --no-daemon verifyContentCatalog verifyEnhancementRules compileClientJava (11s, single-use daemon log BUILD SUCCESSFUL). Prior resumed gate exit0; final gate repeated only after consumer changes.
-69 legacy item values/slots, armor shares/accessory budgets, original quest version/goal/XP, run manifest, immutable maps/snapshot roundtrip;17 invalid-definition inputs and disabled references.
- Actual equipped stat consumer at enhancement0/5/6/15 preserves attack/primary/vitality/spirit; pity MAX_INT saturates100 without overflow.
- Patch attempts failed exact-line matching and Python quoting before mutation; corrected against actual .02/.03 coefficients. Final artifact keeps baseline values.
- No fixture DB quest reward test, live server/GUI/play or run snapshot/mob-data check. Independent QA pending. No deployment/schema/protocol change.

## 2026-10-14 MDF-P1 S10b1
- PASS --no-daemon verifyRunContentSnapshot compileClientJava (31s) against oscar_p1_fixture only. Actual RunRepository reserve/replay/read, version2 stored attack42 vs current/original41, SQL23514 for ID/version/snapshot changes, ordinary PAUSED transition, missing/legacy/mismatch rejection.
- V026 initial fixture apply and corrected compatibility apply PASS; rerun with actual legacy pilot-1/NULL snapshot preserves row unchanged. Reject JSON-array snapshot. All test-owned runs and account removed in finally.
- Initial migration draft assumed missing content_version from summary; source V012 proves existing default. Corrected before commit; fixture initially had no legacy rows, no production changes. Final test exercises real legacy row during rerun.
- No live dungeon entry, runtime mob consumption, deployment/production DB or old-JAR rollout check; follow S10b2 and independent QA.

## 2026-10-14 MDF-P1 S10b2
- PASS --no-daemon verifyContentCatalog verifyBeaconTowerMobs verifyPremergeRuntime verifyRunContentSnapshot compileClientJava (41s); catalog/runtime/DB gates rerun after added invalid-input/range checks PASS37s.
- Actual six baseline mobs'HP/defenses/damage/timing/range/type and boss HP preserved; archived variant changes mob HP/group/scale independently of current. Deep roster immutable,24 invalid-definition cases rejected.
- Actual entry method pins catalog identity and rejects changed snapshot on same encounter; existing movement/ownership/death/reconnect/lifecycle regressions PASS. Minecraft world spawning/rendering/manual play not run.
- New god review condition: actual DB legacy ACTIVE/PAUSED safe-fail + exact return coordinates/survival mode + account-owned ACK, existing reward17 copper/2 ironstone/1 token preserved; terminal completed/failed reads/finish unaffected. Final verifyRunContentSnapshot compileClientJava PASS29s after reward-preservation assertion; prior condition gate PASS34s. Migration rerun preserves pilot-1/NULL legacy row.
- Final fixture accounts0/runs0/schema26. Production DB/PID/JAR/protocol/online-mode untouched; no world/dungeon entry. Creed/Jim S10b2 QA pending.

## 2026-10-14 MDF-P1 S10c1
- PASS --no-daemon verifyContentCatalog verifySkillPresentation verifyCombatConsistency verifyPremergeRuntime verifyRunContentSnapshot compileClientJava (26s, oscar_p1_fixture only for DB gate).
- RED verifySkillContent (11s): empty display name accepted. Added blank/length validation; PASS verifySkillContent verifyContentCatalog compileClientJava (14s).
-24 original names/descriptions/kinds/order, schema1 reference-only metadata fidelity, changed schema2 display/energy independence, immutable weapon list;12 invalid metadata/profession/kind/cost/cooldown/unlock/weapon/revision cases.
- Extracted16 actual cast bodies and shared helper execute baseline energy/cooldown values, no duplicate charge, insufficient stamina, exact expiry and absent state. Existing actual UI max-rank/tooltip and runtime lifecycle/movement/death tests PASS.
- No live packet exchange/GUI/world play or deployment; effect coefficient/per-run equipment and skill definition consumption remains subsequent slices. No schema SQL/protocol/production changes.

## 2026-10-14 MDF-P1 O27 duration correction
- PASS --no-daemon verifyCombatConsistency compileClientJava23s: smoke rank1/5=50/80ticks; shackle40/60ticks; unchanged binding normal/elite-boss policies and effect expiry59/60.
- Initial investigation wrongly inferred shackle reused archer control. Actual source uses a separate vanilla Slowness30+rank*10; corrected diagnosis and restored original binding callbacks/control signature before final23s gate. Intermediate17s gate not evidence of the final routing.
- No DB/migration/protocol/live cast/human play. Effects data extraction follows; this correction changes the two rank-duration endpoints to match PILOT_CONTENT_SPEC.

## 2026-10-14 MDF-P1 S10c2
- Initial compile FAIL13s: escape lacked rank local after definition routing; added guarded server rank local. Next gate FAIL22s: protection fixture lacked shared skill accessor; included actual accessor.
- PASS --no-daemon verifySkillContent verifyContentCatalog verifyCombatConsistency verifySkillPresentation verifyPremergeRuntime verifyRunContentSnapshot compileClientJava32s, diff check.
-24 effect tables rank0/1/3/5 interpolate, capability key/malformed/decreasing/negative tables reject; historical schema1/schema2 smoke90/shackle80 preserved against schema3 corrected80/60. Changed stored mark bonus/duration ownership and39/40 expiry; explicit shield11/12 lifetime. Existing cast16 cost/cooldown/ownership, metadata12 invalid cases and4 new parameter cases PASS.
- Actual world handlers compile and consume values, but live Minecraft damage/control/geometry/GUI not exercised. Definition accessor still current catalog until S10c3 pins combat/equipment lookup. No deployed DB/protocol/live state changes.

## 2026-10-14 MDF-P1 S10c3
- PASS --no-daemon verifySkillContent verifyContentCatalog verifyCombatConsistency verifyPremergeRuntime verifyRunContentSnapshot compileClientJava26s, diff check.
- Actual cast accessor+content provider execute changed archived sweep energy21 (stamina300->279) against current20, preserving all16 cast gates. Actual runtime content returns pinned party energy21, rejects changed snapshot, returns current catalog after close.
- Real PilotEquipment supplied-catalog calculation uses changed attack/primary bonus/enhancement scale:77 attack/6 primary vs69/4 current. Existing owner/slot/level guards retained.
- No SQL migration/protocol/live exchange/dungeon entry or actual Minecraft profile attributes; independent QA needed. No production PID/DB/JAR/settings changes.

## 2026-10-15 MDF-P1 S10c4
- Initial metadata/skill/GUI/runtime gates PASS, DB archive fixture FAIL29s because new required display/content revision was not updated in synthetic archive. Updated both fields; catalog/skill/GUI/DB/compile PASS24s.
- Added exact supported weapon-profile coverage; targeted skill/compile PASS16s. Metadata30 rejection inputs include duplicate IDs, missing display, blank names, version mismatch and disabled critical table/part. Known generated labels/localized descriptions, unknown legacy IDs and schema3 original labels verified.
- Extended actual quest init fixture: first fixture compile FAIL11s from missing Component.empty stub; corrected, then RED12s reproduced Clipped action320x180. Adaptive quest width fixed; actual skill/run/quest/profile/equipment bounds/paging and compile PASS15s.
- Bumped current definition to pilot-j02-2, kept historic pilot-j02-1 and quest pilot-1. Catalog+skill PASS18s; DB immutability fixture FAIL because it wrote identical newly-current version2, correctly allowed. Changed negative case to different version3; final DB+compile PASS16s. Final fixture accounts0/runs0.
- No live rendering/attribute/network/dungeon entry, SQL migration/protocol/deployment/production changes. Independent Creed/Jim QA pending.

## 2026-10-15 MDF-P1 S09d1
- PASS --no-daemon verifySkillJournal verifyRequestJournalResult compileClientJava18s. Updated rank merge/unrelated-profile fixture then verifySkillJournal compileClientJava14s PASS; diff check.
- Actual DB learning twice then UUID1 replay returns original rank1/current rank2/points4, original event identity, altered correlation no new charge. Legacy same long replay charge once, concurrent UUID executes once, changed body/stale generation/wrong actual job/level9 reject.
- Unique fixture-only trigger injects failure after point debit, proving points/rank/journal rollback; trigger/function cleaned. Actual network cache helper receives reverse/replayed/unrelated-profile results without reducing rank. Current ranks/profile share locked SQL snapshot.
- Final oscar_p1_fixture accounts0/operation_requests0. No client UUID skill dispatch/live request/server/world/migration/protocol changes; application ACK and reconnect recovery remain M11.

## 2026-10-15 MDF-P1 S09d2
- Scripted exact-match edit stopped before client patch (server/profile-state portion applied); corrected actual multi-line method and continued only client stage. Intermediate backend/client legacy gates PASS17s; common client compile/recovery/envelope gates PASS12s.
- Final PASS --no-daemon verifySkillWireReceipt verifyClientReplyRecovery verifyClientRequestEnvelope verifySkillJournal compileClientJava19s, diff check.
- Actual receipt parser and completion method replay status/latest-query counters, reject/missing fields/foreign skill/fractional and rank6 malformed results/wrong correlation. Actual ClientProfileState100tick typed resend disabled after UUID delegation, wrong completion ID ignored, exact completion unlocks. Existing same-object UUID retry/context/selection and real DB charge/replay/rollback/cache tests retained.
- No running Minecraft client/server request exchange, production data or application ACK/reconnect recovery. No migration/protocol/new packet format. M11 handles durable application status.

## MDF-P1 S09e1 (2026-10-15)
- PASS: --no-daemon verifyStatJournal verifyCharacterStatInvestment (12초). UUID/다른 correlation 재생, 원결과·최신 상태 구분, legacy 재생·과거 요청 채택, 동시 요청 한 번 차감, 본문/세대/잔여 점수 거부, 차감 이후 INSERT 실패 주입 시 차감·저널 롤백.
- 첫 실행은 시험 데이터 정리 외래 키 오류로 FAIL. 정리 순서를 수정하고 해당 fixture를 제거한 뒤 재검사 PASS. 운영·인게임 검사는 실행하지 않았습니다.

## MDF-P1 S09e2 (2026-10-15)
- PASS: --no-daemon verifyStatWireReceipt verifyClientReplyRecovery verifyClientRequestEnvelope verifyStatJournal compileClientJava (22초). 실제 투자 receipt parser의 재생/거부/틀린 correlation/잘못된 정수·본문, common 중 5초 timeout 해제 금지 및 legacy timeout 유지, 공통 UUID 동일 봉투 retry·선택 경계, fixture 저널 재검증.
- 인게임 및 실제 패킷 왕복 검증은 미실행. 독립 QA 요청 대상입니다.

## MDF-P1 S09e3 (2026-10-15)
- PASS: --no-daemon verifyResetJournal verifyStatJournal verifyCharacterStatInvestment (17초). 초기화 UUID/다른 correlation/동시 요청 한 번 환급/legacy 채택·재생, 원결과·이후 투자 최신 상태, 본문·세대·무료 초기화 중복 거부, 환급 뒤 INSERT 실패 시 능력치·잔여 점수·무료 자격·저널 롤백.
- 운영·인게임 검사는 미실행. 독립 QA 요청 대상입니다.

## MDF-P1 S09e4 (2026-10-15)
- PASS: --no-daemon verifyResetWireReceipt verifyClientReplyRecovery verifyClientRequestEnvelope verifyResetJournal compileClientJava (24초). 실제 초기화 receipt parser의 replay/reject/틀린 correlation/잘못된 초기화 수치·정수·무료자격, typed→common 단일 재시도 및 기존 client 복구·UUID 경계/fixture 초기화 롤백.
- 실제 패킷 왕복·인게임 및 M11 실제 world ACK/reconnect는 미검증/미완료. 독립 QA 요청.

## MDF-P1 S09f1 (2026-10-15)
- PASS: --no-daemon verifyProfessionJournal verifyCharacterProfession (15초). UUID/다른 correlation/동시 요청/legacy 반복 초기점수 한 번 지급, replay 최신 점수, 레벨·의뢰 선행조건/다른 직업·본문·세대 거부, 직업 INSERT 이후 점수 UPDATE 실패 시 직업·보상·저널 전체 롤백.
- 운영·인게임 미실행. 독립 QA 요청 대상입니다.

## MDF-P1 S09f2 (2026-10-15)
- PASS: --no-daemon verifyProfessionWireReceipt verifyClientReplyRecovery verifyClientRequestEnvelope verifyProfessionJournal compileClientJava (12초). 실제 전직 receipt parser의 replay/reject/틀린 correlation/다른 직업·잘못된 본문, typed/common 단일 재시도 및 최신 기술/장착 조회, 공통 client 경계·fixture 전직 원자성 확인.
- 앞선 검사 시 compileClientJava가 다수 기존 상태 클래스를 찾지 못해 FAIL. 소스 파일 존재/빌드 설정 범위/실행 중 Gradle 없음 확인 후, 소스 변경 없이 compileClientJava --rerun --info 전체 재컴파일 PASS(7초), 위 gate 재검사 PASS. 산출물·증분 상태 문제의 발생 원인은 미확인입니다.
- 실제 패킷 왕복·인게임·개발 명령 인게임 검사는 미실행. M11 world ACK/reconnect는 미완료. 독립 QA 요청 대상입니다.

## MDF-P1 S09g1 (2026-10-15)
- PASS: --no-daemon verifyLoadoutJournal (10초). UUID/다른 correlation/동시 단일 revision/legacy 원결과 채택, 원결과·최신 장착 분리, 본문·세대·버전·passive·중복·null 거부, 슬롯 변경 뒤 이력 INSERT 실패 시 슬롯/revision/저널 rollback, ACTIVE 변경 거부·읽기 재생 허용, 종료 후 변경과 고정 스냅샷 보존.
- 최초 실행은 검사 본문 통과 후 cleanup FK 오류로 FAIL. 요청 이력 정리 순서를 수정하고 해당 계정 fixture를 제거한 뒤 재검사 PASS. 기존 verifyLoadoutDatabase는 별도 oscar_fixture 전용이라 실행하지 않았습니다. oscar_p1_fixture만 사용했으며 실제 던전 입장은 실행하지 않았습니다.

## MDF-P1 S09g2 (2026-10-15)
- PASS: --no-daemon verifyLoadoutJournal verifyClientReplyRecovery verifyClientRequestEnvelope compileClientJava (16초). 월드잠금 새 쓰기 거부/기존 UUID 읽기재생, 장착 대기 만료 중 common 단일 재시도·틀린 correlation 무시·완료 후 최신 캐시 보존, 공통 UUID 경계 및 장착 DB 회귀.
- 첫 컴파일은 router ArrayList 클래스 한정 누락으로 FAIL. 한정명 수정 후 위 검사 PASS. 마지막 파티 준비 해제 조건 이동 뒤 compileJava compileClientJava PASS(8초).
- 실제 패킷 왕복·파티 준비 알림·인게임 조작은 미실행. M11 실제 world ACK/reconnect는 남습니다. 독립 QA 요청 대상입니다.

## MDF-P1 M12a (2026-10-15)
- PASS: --no-daemon verifyOperatorLedger compileJava (10초, compileClientJava 포함). oscar_p1_fixture에만 V027 적용. 구리 지급·같은 요청 재생·이후 지급과 저장 잔액 구분, 초과 회수 롤백, 빈 사유·0 증감·다른 본문 거부, 기술점·재료 지급과 음수 회수 거부를 확인했습니다. 구리 행은 기존 currency ledger에 공략 없이 같은 eventId로 1건만 남습니다. 검사 계정과 원장 행은 정리했습니다.
- 운영자 명령, 인게임, 라이브 서버, 운영 DB는 실행하지 않았습니다.

## MDF-P1 M12b (2026-10-15)
- PASS: --no-daemon verifyOperatorLedger compileJava (12초, compileClientJava 포함). 명령 경로로 구리 +1, 같은 요청 번호 재생, 다른 캐릭터 거부를 확인했습니다. 잔액은 51에서 멈추고 다른 캐릭터에는 쓰지 않았습니다.
- 게임 안에서의 명령 입력, 권한 없는 플레이어, 라이브 서버는 실행하지 않았습니다.

## MDF-P1 S09h1 (2026-10-15)
- PASS: --no-daemon verifyEquipmentMoveJournal (13초). UUID/다른 correlation/동시 한 번 이동/legacy 채택, 원슬롯·최신 목록 분리, 본문·세대·비활성 캐릭터 장비·월드 잠금·레벨 거부, 교체 장비 밀어내기·귀속 뒤 이력 INSERT 실패 시 전체 rollback, 재시도 성공 후 교체·귀속 확인.
- 실제 패킷 왕복·인게임 이동은 미검증. 독립 QA 요청 대상입니다.

## MDF-P1 S09h2 (2026-10-15)
- PASS: --no-daemon verifyEquipmentMoveJournal verifyClientReplyRecovery verifyClientRequestEnvelope compileClientJava (14초). 장비 이동 DB regression, 실제 이동 receipt handler의 저장결과/거부 안내·최신목록 조회·캐시 덮어쓰기 금지, 공통 UUID/context/선택/동일봉투 retry 경계 검사.
- 최종 편집 가능 replay 전투 갱신 조건 수정 뒤 compileJava compileClientJava PASS(9초). 실제 패킷 왕복·월드 반영 실패주입·인게임 이동은 미실행. 독립 QA 요청 대상입니다.
## MDF-P1 M08 (2026-10-15)
- PASS: --no-daemon verifyRunEncounterRules verifyPremergeRuntime compileJava (16초, compileClientJava 포함). 잔류 0/9999ms 포함·10000ms 제외, 사망자는 생존자 아님, 잔류 중 보스 진행·창 종료 후 정지, 잔류 중 파티 전멸 보류·창 종료 후 전멸.
- 실서버 잔류 개체 피격·오프라인 전멸의 DB 실패 기록·운영 DB 즉시 PAUSED는 미실행. 독립 QA 요청 대상입니다.

## MDF-P1 M08 통합 (2026-10-15)
- PASS: --no-daemon verifyRunEncounterRules verifyPremergeRuntime verifyLoadoutJournal verifyEquipmentMoveJournal compileJava compileClientJava 1회 실행 (12초). M08 규칙/생산 메서드 회귀와 O28 장착·장비 이동 fixture 회귀 확인.
- 운영·실제 접속 종료·인게임 조작 미실행. 잔류 사망 뒤 DB 런 FAILED 전이는 M11 추가 구현 대상으로 남습니다.

## MDF-P1 S09i1 (2026-10-15)
- PASS: --no-daemon verifyRefinementJournal verifyEquipmentMoveJournal (14초). UUID/다른 correlation/동시 한 번 차감·추첨·원장, 저널event-원장 연결, legacy 원결과 채택/최신 재련 분리, 본문·소유권·재접속 전 세대(long/UUID) 거부, 차감·추첨 변경 후 INSERT 실패 시 비용/상태/저널/원장 전체 rollback, 같은UUID 재시도 성공.
- 재련 실행 caller/UI/패킷은 현재 없으며 인게임 검사는 미실행. 독립 QA 요청 대상입니다.

## MDF-P1 M13a 통합 (2026-10-15)
- PASS: --no-daemon compileJava (5초) 1회. merge 결과 신규 스크립트·증적 문서 2개 추가, 충돌 없음.
- 복원훈련은 Oscar가 재실행하지 않았습니다. Creed/Jim PASS 및 RTO 2.656초는 god의 독립 검증 통보이며 이번 컴파일 검사와 구분합니다.

## MDF-P1 S09i2 (2026-10-15)
- PASS: --no-daemon verifyRefinementWireReceipt verifyRefinementJournal verifyClientReplyRecovery verifyClientRequestEnvelope verifyOperationWire compileClientJava (17초). 실제 재련 receipt parser의 replay/reject/누락·분수·등급범위·실패음수·boolean위조·다른장비 거부/원결과 캐시미적용/최신조회. 현재 상태 지연응답·범위·선택 초기화, 조회 owner·세대 검증, 새 조회/응답 codec long 최대 인근 roundtrip, 기존 UUID 경계/DB 재련 regression.
- 실제 패킷 왕복·인게임 재련은 미실행. 전투 재련효과 미연결로 일반 UI 버튼은 추가하지 않았습니다. M11 실제 world ACK/reconnect는 남습니다. 독립 QA 요청 대상입니다.

## MDF-P1 S09j1 (2026-10-15)
- PASS: --no-daemon verifyInheritanceJournal verifyRefinementJournal (16초). UUID/다른 correlation/동시 단일 원본 소모/legacy 채택, 원이전액·최신잔액/목록 분리, 계승 대상까지 소모된 뒤 저장 UUID 재생, 귀속 병합, 본문·비활성캐릭터 장비·계열·레벨·재접속 전 세대 거부, 원본 DELETE 뒤 이력 INSERT 실패 시 잔액/원본/저널 rollback 및 동일UUID 재시도.
- 기존 계승 계산식의 설계 불일치는 유지된 한계이며 검사가 설계 전체 준수를 뜻하지 않습니다. 실행 caller/UI/인게임은 현재 없고 미검증입니다. 독립 QA 요청 대상입니다.

## MDF-P1 S09j2 (2026-10-15)
- PASS: --no-daemon verifyInheritanceWireReceipt verifyInheritanceJournal verifyClientReplyRecovery verifyClientRequestEnvelope compileClientJava (19초). 실제 계승 receipt parser replay/reject/원이전액 캐시미적용·원본미복원·최신조회, 누락/분수/int넘침/음수/다른원본·대상 거부. 계승 DB 원자성/소모대상재생 및 공통 UUID/context/재시도 경계 회귀.
- 실제 패킷 왕복·인게임은 미실행. P1 UI/일반호출자 노출 없으며 경제식 재구현은 P3입니다. 독립 QA 요청 대상입니다.
## MDF-P1 M11a (2026-10-15)
- PASS: --no-daemon verifyRunWipe compileJava (16초, compileClientJava 포함), verifyPremergeRuntime (7초). oscar_p1_fixture에서 PAUSED 런만 FAILED가 되고, 같은 호출은 한 번만 적용되며, COMPLETED와 주간 횟수 1은 유지되고 active는 null입니다.
- 잔류 개체를 실제로 죽이는 인게임 전멸, 재접속, 라이브 서버는 실행하지 않았습니다.

## MDF-P1 M11a 통합 (2026-10-15)
- PASS: --no-daemon verifyRunWipe verifyPremergeRuntime verifyRunEncounterRules compileJava compileClientJava 1회 실행(14초). 잔류 전멸 실패 저장/기존 종료 상태 보호·기존 runtime/규칙 회귀 확인.
- 운영/실제 접속종료·인게임 조작 미실행. M11 미반영 이벤트·재접속 ACK는 별도 미완료입니다.
## MDF-P1 M12b 수정 (2026-10-15)
- PASS: --no-daemon verifyOperatorLedger compileJava (12초, compileClientJava 포함). oscar_p1_fixture에서 기존 operator_adjustments만 지우고 V027을 다시 적용했습니다. 다른 계정 캐릭터 지급, 운영자 계정과 대상 계정 분리, 선택하지 않은 대상, 같은 요청 재생, 초과 회수 거부, 대상 계정 잠금 대기 후 지급을 확인했습니다.
- 접속 중인 대상에게 최신 프로필을 보내는 것, 인게임 명령, 권한 없는 플레이어, 라이브 서버는 실행하지 않았습니다.

## MDF-P1 M12c (2026-10-15)
- PASS: --no-daemon verifyOperatorLedger compileJava (12초, compileClientJava 포함). 명령 등록이 기존 sendCharacterProfile을 호출하는지만 확인했습니다. 접속 중인 플레이어에게 패킷이 도착하는 것은 실행하지 않았습니다.

## MDF-P1 M12 통합 (2026-10-15)
- PASS: --no-daemon verifyOperatorLedger verifyRunWipe verifyPremergeRuntime verifyEquipmentMoveJournal verifyRefinementJournal verifyInheritanceJournal compileJava compileClientJava 지정8개1회 실행(24초). 운영자 원장·전멸·기존 런/장비 request 회귀 확인.
- 운영 명령 실서버 실행/실제 지급·회수는 미실행. 운영 DB/인증/online-mode/push 변경 없음. M11b 별도 진행 중.

## MDF-P1 S09k1 (2026-10-15)
- PASS: --no-daemon verifyQuestAcceptanceJournal (10초). UUID/다른 correlation/동시 단일 수락/long재생, 원수락결과·2/3진행·REWARDED 최신상태 분리, 재보상·진행초기화 없음, 버전/비활성캐릭터/선택전캐릭터/이전세대 거부, 의뢰 INSERT 뒤 저널확정 UPDATE 실패 시 의뢰·저널 rollback 및 동일UUID 재시도.
- 새 live wire 미연결·자동 처치 이벤트 journal 미구현·인게임 미실행. 독립 QA 요청 대상입니다.

## MDF-P1 S09k2 (2026-10-15)
- PASS: --no-daemon verifyQuestAcceptanceWire verifyQuestAcceptanceJournal verifyClientReplyRecovery verifyClientRequestEnvelope compileClientJava (18초). 실제 receipt parser의 replay/reject/quest·version 불일치·created 누락/문자boolean 거부, 원created로 REWARDED 진행상태 미덮어쓰기/읽기조회, 실제 QuestState 선택후 이전response 무효화/요청counter유지, 공통 UUID/context/retry 및 DB 원자성 회귀.
- 실제 패킷 왕복/인게임/개발 명령 조작은 미실행. 자동kill writer와 run 저널/M14는 아직 남습니다. 독립 QA 요청 대상입니다.

## QA MDF-P1 S09k3 (2026-10-15)
- PASS: --no-daemon verifyQuestKillJournal verifyQuestAcceptanceJournal compileJava compileClientJava (16초). 동시 동일처치/미수락 no-op replay/자동보상 일회/원결과 재생 억제/확정 실패시 진행·XP·journal rollback/동일entity retry/old generation 거부/DB-only completed 확인.
- fixture oscar_p1_fixture localhost:15432: accounts0/operation_requests0/schema27. 실제 게임·패킷 왕복·운영 서버는 미검증. 실패 이벤트의 프로세스 재시작 후 자동 재수집은 이번 범위에 없으며 오류는 로그로 남깁니다. 독립 QA 대기, 전체 PARTIAL.


## MDF-P1 M11b (2026-10-15)
- PASS: --no-daemon verifyRequestJournal verifyClientRequestEnvelope compileJava compileClientJava (19초). 재접속 세대의 같은 UUID는 저장 결과와 같은 강화 이벤트를 돌려주고 구리를 다시 차감하지 않았습니다. 이전 세대 패킷과 다른 본문은 거부했습니다. 클라이언트는 끊긴 동안 재전송하지 않다가, 같은 캐릭터의 새 세대가 오면 같은 UUID를 다시 보냅니다.
- 인게임 끊김·패킷 도착·라이브 서버·운영 DB는 실행하지 않았습니다.

## MDF-P1 M11b 수정 (2026-10-15)
- 기준 b8779d1: 실패했던 6개 검사 PASS (26초). 그 결과는 잔여 행이 아니라 잠금 순서 회귀를 가릅니다.
- 수정 후 --no-daemon verifySkillJournal verifyStatJournal verifyResetJournal verifyProfessionJournal verifyLoadoutJournal verifyRefinementJournal verifyRequestJournal PASS (24초). 인게임 끊김은 실행하지 않았습니다.

## MDF-P1 run writer (2026-10-15)
- PASS: --no-daemon verifyRunJournal verifyCharacterRun (10초). 같은 입장 요청은 런 하나와 저널 한 행만 만들고, 난이도를 바꾼 재사용은 거부했습니다. 인게임 입장과 운영 DB는 실행하지 않았습니다.

- 2026-10-15 MDF-P1 M14a: PASS --no-daemon compileJava compileClientJava testClasses verifyProtocolVersion verifyOperationWire (15s). 상수 31, Hello/Welcome 코덱 왕복, 서버 불일치 시 disconnect→return이 compatible/Welcome보다 앞섬, 클라이언트 동일 버전 Welcome만 수락(소스 순서 검사). 실제 불일치 접속은 미실행.

## MDF-P1 g231 + M14a 통합 회귀 (2026-10-15)
- --no-daemon p1_regression.ps1 1회, exit0, 전체69.85초. 검사 작업은 up-to-date로 건너뛰지 않고 실제 실행되었으며 임시서버 RunRespawnMixin 적용/ready/주입오류없음 PASS. smoke 내부 assemble PASS(6초), protocol31 client 포함 JAR 생성.
- M11b `73441a0`, run writer `b50e166`, M14prep `5f9bdf5`, protocol31 `351079c`. 운영서버·운영DB·운영JAR 변경 없음. fixture 사후 accounts1/requests0/schema27, 잔여계정 characters0/주체미확인이라 미삭제. 인게임·독립 통합QA 미실행. 상세 원문은 hive/research/MDF_P1_G231_OSCAR_regression.log.
| 검사 | 실제 실행 | 결과 |
|---|---|---|
| `verifyRunJournal` | 예 | PASS |
| `verifyClientScreenBounds` | 예 | PASS |
| `verifyClientReplyRecovery` | 예 | PASS |
| `verifyEnhancementReplay` | 예 | PASS |
| `verifyRequestJournal` | 예 | PASS |
| `verifyClientRequestEnvelope` | 예 | PASS |
| `verifyRequestJournalResult` | 예 | PASS |
| `verifySkillJournal` | 예 | PASS |
| `verifyStatJournal` | 예 | PASS |
| `verifyResetJournal` | 예 | PASS |
| `verifyProfessionJournal` | 예 | PASS |
| `verifyLoadoutJournal` | 예 | PASS |
| `verifyEquipmentMoveJournal` | 예 | PASS |
| `verifyRefinementJournal` | 예 | PASS |
| `verifyInheritanceJournal` | 예 | PASS |
| `verifyQuestAcceptanceJournal` | 예 | PASS |
| `verifyQuestAcceptanceWire` | 예 | PASS |
| `verifyQuestKillJournal` | 예 | PASS |
| `verifyProtocolVersion` | 예 | PASS |
| `p1_integration_smoke.ps1` | 예 | PASS |

- 2026-10-15 MDF-P1 M14b: PASS --no-daemon verifyRunAutoRespawnSafety (8s, compileTestJava 재실행). null·connected=false는 호출 없음. 삼킨 패킷 3회와 닫힌 연결 예외는 차감 0. 완료 부활은 재생 포함 1회. 다른 플레이어·런 밖 플레이어 불변. tick의 autoRespawn 호출은 try 밖. 실제 ServerGamePacketListenerImpl·가짜 플레이어 연결은 미실행.

## M14b 자동 부활 예외 격리 (2026-10-15)
- ced89aa 검사 브랜치 로컬 --no-ff 머지 8a378d8. Java 충돌 없음, 문서 충돌 양쪽 기록 보존.
- RunRuntime.autoRespawn의 handleClientCommand만 RuntimeException을 잡아 UUID·예외 클래스 경고와 false를 반환합니다. 기존 조건·부활 횟수 차감 로직 유지, Error는 전파합니다.
- 실제 메서드 본문 하네스로 실패 연결 뒤 다음 플레이어 처리/부활 횟수 유지/경고 UUID·클래스/Error 동일 객체 전파/성공 부활1회·재생중복방지 검증. 기존 현상 확인용 예외 누출 단언을 교체했습니다.
- p1_regression.ps1에 verifyRunAutoRespawnSafety 포함, 실제1회 exit0/68.67초, 검사20개+임시서버smoke1개 PASS. 내부 assemble PASS6초, protocol31 JAR 재생성 sha256 56b9847ffa4cb7c3e4a3dda6282b36932f5223dbb68750923ce9ec3fc294d68b.
- fixture localhost15432/oscar_p1_fixture 사후 accounts1/requests0/schema27. 기존 출처미확인 계정 삭제없음. 실제 closing/fake Minecraft 연결·인게임 미실행. 운영DB/PID14160/인증/online-mode/배포/push 변경없음. 독립리뷰 god요청, 전체P1 PARTIAL.

- 2026-10-15 P2-S01: PASS --no-daemon verifyStarterSupply (18초). oscar_p1_fixture에 V028 적용. 생성 시 구리 20·회복 4·귀속 수련 장비 1·지급 행 1. 같은 요청 재생과 starter 재호출 후에도 수량 불변. 다른 캐릭터는 따로 1회. supply.training_gear·supply.recovery는 거래 불가, 전직 무기는 그대로. 검사 계정은 정리. 인게임 미실행.

- 2026-10-15 P2-S01 검사 수정: PASS tools/p1_regression.ps1 exit 0 (64초), DALMARU_DB_NAME=oscar_p2_fixture. 검사 20개와 p1_integration_smoke PASS. verifyRequestJournal, verifyRefinementJournal, verifyRunRewards PASS (13초). verifyStarterSupply PASS (7초). 제품 지급 코드는 그대로. 인게임 미실행.

- 2026-10-15 P2-S02: PASS --no-daemon verifyGrowthRewardGrant verifyStarterSupply (10초, compileTestJava 재실행). DALMARU_DB_NAME=oscar_p2_fixture, V028 적용. 전사 키트 6개 1회, 재호출 불변. 강화 3인 무기를 15레벨로 교체해도 강화 3 유지. 조사 후 구리 60·회복 8·수련 장비 유지. 무직 조사는 롤백. 궁수는 궁수 장비만. 10·15레벨 직업 장비는 거래 불가, 20레벨 무기와 장신구는 가능. 검사 계정 정리. oscar_p1_fixture의 V028은 그대로 두고 더 쓰지 않음. 인게임 미실행.

- 2026-10-15 P2-S04: PASS verifyCharacterDeletion (9초, compileTestJava 재실행). DALMARU_DB_NAME=oscar_p2_fixture, V029 적용. 7일 안 슬롯·이름 유지, 재삭제는 시각 불변, 복구 후 무료 초기화 불가와 클리어 2회 유지, 선택은 삭제 중에만 거부. 8일 뒤 목록이 행을 지우고 새 캐릭터는 20전 1회. 다른 계정의 이름도 7일 유지. tools/p1_regression.ps1 exit 0. 인게임 미실행.

- 2026-10-15 P2-S09: PASS verifyPaidStatReset (8초). DALMARU_DB_NAME=oscar_p2_fixture. 무료 1회는 전 0에서도 능력치만 되돌림. 잔액 9전은 거절하고 능력치 불변. 30전에서 10전을 한 번 걷고 재생은 추가 차감 없음. 5레벨은 50전. 원장 행은 유료만. verifyResetJournal PASS. tools/p1_regression.ps1 exit 0, 20개 검사와 smoke PASS. 인게임 미실행.

- 2026-10-15 P2-S10: PASS verifyVillageRoutes (9초). 태평현에서 직업 4마을로 가고, 각 마을은 태평현으로 돌아온다. 주민 외형은 minecraft:villager, 대사는 초안. 구역은 겹치지 않는다. DB 없음. tools/p1_regression.ps1 exit 0, DALMARU_DB_NAME=oscar_p2_fixture, 20개 검사와 smoke PASS. 인게임 미실행.
| 검사 | 실제 실행 | 결과 |
|---|---|---|
| `verifyRunJournal` | 예 | PASS |
| `verifyClientScreenBounds` | 예 | PASS |
| `verifyRunAutoRespawnSafety` | 예 | PASS |
| `verifyClientReplyRecovery` | 예 | PASS |
| `verifyEnhancementReplay` | 예 | PASS |
| `verifyRequestJournal` | 예 | PASS |
| `verifyClientRequestEnvelope` | 예 | PASS |
| `verifyRequestJournalResult` | 예 | PASS |
| `verifySkillJournal` | 예 | PASS |
| `verifyStatJournal` | 예 | PASS |
| `verifyResetJournal` | 예 | PASS |
| `verifyProfessionJournal` | 예 | PASS |
| `verifyLoadoutJournal` | 예 | PASS |
| `verifyEquipmentMoveJournal` | 예 | PASS |
| `verifyRefinementJournal` | 예 | PASS |
| `verifyInheritanceJournal` | 예 | PASS |
| `verifyQuestAcceptanceJournal` | 예 | PASS |
| `verifyQuestAcceptanceWire` | 예 | PASS |
| `verifyQuestKillJournal` | 예 | PASS |
| `verifyProtocolVersion` | 예 | PASS |
| `p1_integration_smoke.ps1` | 예 | PASS |
