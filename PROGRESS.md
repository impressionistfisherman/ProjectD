# 현재 상태

2026-09-28: CTP-001의 1인 최소 루프를 구현했다. 폐봉수대는 철문수장 방·화로 구역·원혼봉수장 방·보상 구역의 실제 블록 구조와 단계별 철문을 생성한다. 철문수장과 원혼봉수장은 장비·이름·체력·단계별 공격을 갖는 바닐라 좀비 기반 보스이며, 최종 처치 뒤 보상 상자를 연다. 실제 인게임 동선·패턴·보상 상호작용 확인은 남았다.

2026-09-28: 폐봉수대 콘텐츠 제작 계획 `CTP-001`을 구현 전 기준으로 확정했다. 보스 2종의 외형·패턴·처치 결과, 단방향 5구역 맵 흐름, 바닐라 좀비 기반 제작 방식을 정의했다. 다음 구현은 1인 최소 플레이 루프이며 멀티플레이·재접속·보상 검증 확대는 그 뒤로 보류한다.

> 문서 ID: STA-001 · 분류: 현황·증적 · 상태: 현재 기록
- 갱신: 2026-09-26 02:25:00 +09:00
- 이번 변경: 캐릭터 프로필이 준비되면 채팅창 위에 기력 바 HUD를 표시한다. 현재 기력 소모 기능이 아직 없으므로 바는 최대 상태이며, 향후 서버 기력 소모·동기화 패킷이 추가되면 같은 HUD에 현재값을 연결한다.
- 이번 수정: 퀘스트를 `J` 프로필 하단 버튼에서 분리해 `U` 전용 키로 연다. 바닐라 `Q` 버리기 키와 충돌하지 않으며, 프로필 밖으로 밀려 보이지 않던 버튼을 제거했다.
- 검증: PostgreSQL 계정·캐릭터·능력치·경험치·전투 저장소 검증과 localhost 멀티플레이 접속을 통과했다. 전직·퀘스트의 버튼별 최종 인게임 조작은 게임 창 자동화 표면 미노출로 남았다.
- 이번 수정: 캐릭터 목록 요청 제한 응답은 1.25초 뒤 한 번 자동 재시도한다. 서버의 1초 중복 요청 제한은 유지하며, 사용자에게 실패 문구를 표시하지 않는다.
- 이번 변경: `J` 프로필에 퀘스트 화면을 추가했다. 플레이어는 전용 요청·응답으로 좀비 토벌 의뢰의 상태를 확인·수락하며, 기존 처치 3회·100 경험치 보상 저장소를 재사용한다. 통신 버전은 9다.
- 다음 작업: 기력 UI와 무기별 기본 공격 피해를 연결한다. 이번 전직·퀘스트 통신의 빌드·인게임 검증은 사용자 요청에 따라 보류한다.
- 이번 변경: `J` 전직 선택은 개발 명령 대신 전용 요청·응답으로 처리한다. 서버는 활성 캐릭터·접속 세대·레벨 10·`pilot1.zombie_hunt` 보상 완료·최초 선택을 검증하고, 승인 결과와 저장 프로필을 클라이언트에 돌려준다. 통신 버전은 8이다.
- 다음 작업: 인게임에서 전직 조건 충족·거부·승인 뒤 재접속 유지와 기본 공격의 무기별 피해를 수동 확인한다.
- 가제: 달마루. 개발용 식별자는 `MCRPG`를 유지한다.
- 문서 체계: `docs/DOCUMENT_CONTROL.md`를 정본 문서 관리 기준으로 추가하고, 현행 제작·게임 문서에 문서 ID·분류·상태를 부여했다.
- 이번 변경: V004로 캐릭터별 시작 프로필을 추가했다. 새 캐릭터는 레벨 1·경험치 0·미배분점 0·힘/재주/도력/체력/정신 0·무료 초기화 가능 상태로 생성되며 기존 캐릭터도 안전하게 채웠다.
- 이번 변경: 접속마다 계정 준비 완료 뒤 캐릭터 선택·생성 화면을 자동으로 열고, 선택 응답을 받기 전에는 화면을 닫아도 다시 열도록 했다. 선택 확정 뒤에만 게임 화면으로 돌아간다.
- 이번 변경: 선택 확정 뒤 서버가 해당 캐릭터의 프로필을 조회해 전달한다. `P` 키 정보 화면에서 레벨·경험치·미배분점·다섯 직접 능력치·무료 초기화 상태를 표시한다.
- 이번 변경: 마인크래프트 기본 사회적 상호작용 키와 충돌한 프로필 단축키를 `J`로 변경하고, 목록 재요청 대기 시간을 서버 제한보다 길게 조정했다. `J`는 키 바인딩 충돌을 피하기 위해 인게임 실제 키 입력을 직접 감지해 즉시 프로필 화면을 연다.
- 이번 변경: 프로필 값을 버튼 기반 화면으로 표시하고, 미배분점이 있을 때 다섯 능력치에 `+1` 투자할 수 있게 했다. 투자 요청은 활성 캐릭터·연결 세대·미배분점·요청 멱등성을 서버가 검증한다. 인게임 다른 캐릭터 선택은 저장 뒤 재접속을 요구해 상태 혼합을 막는다.
- 이번 변경: 캐릭터별 최초 1회 무료 능력치 초기화를 V006 요청 기록과 함께 추가했다. 직접 투자한 다섯 능력치만 미배분점으로 반환하며, 무료 사용 상태는 서버가 확정한다.
- 설계: D01~D16 세부 결정은 담당 문서에 반영했다. 일반 던전 클리어 한도는 사용자 의도에 맞춰 캐릭터·콘텐츠·난이도별 주 3회로 통일했다.
- Active Spec: [docs/PHASE_01_DESIGN.md](docs/PHASE_01_DESIGN.md) — 첫 연결 설계. `PLT-002` 완료 조건을 확인했다.
- 구현: `server-project/`에 게임 코드와 Gradle 설정이 있다. 상위 저장소가 설계와 구현 변경을 함께 관리한다.
- 확인: 설계 문서 상대 링크 15개 파일 통과. 요구사항·규칙·콘텐츠·데이터 계약의 상호 참조와 기존 일일/주간 횟수 충돌을 점검했다.
- 이번 변경: 서버 저장소에 경험치 누적·레벨업 계산을 추가했다. 다음 레벨 필요 경험치는 `100 + 35 × (현재 레벨 - 1)`이며, 60레벨까지 레벨업마다 미배분점 5점을 준다.
- 이번 변경: 개발·운영자용 `/dalmaru xp <수치>` 명령을 추가했다. 현재 선택한 캐릭터에만 서버가 경험치를 지급하고, 갱신한 프로필을 즉시 클라이언트에 전달한다.
- 이번 변경: 서버가 적 처치를 확정한 뒤에만 좀비·거미·스켈레톤·크리퍼·마녀·엔더맨의 시험 경험치를 지급하도록 연결했다. 처치자는 갱신된 프로필과 획득 문구를 받고, 비전투 동물은 보상 대상에서 제외한다.
- 이번 변경: 전투 수치를 서버 공통 계산으로 분리했다. 레벨·체력·정신·주력 능력치에서 생명력·기력·초당 회복·주력 계수를 계산하며, 무기 공격력·기술 배율·치명타·방어의 피해 공식과 방어 75% 상한을 동일하게 적용한다.
- 이번 변경: 실제 유효 피해 뒤에만 전투 상태를 갱신하는 서버 런타임을 추가했다. 캐릭터 선택 시 기력 최대치와 회복 값을 준비하고, 피해 뒤 10초가 지나면 기력을 초당 회복한다. 이 상태는 DB 프로필과 분리된 접속 중 상태이며, 접속 종료 때 제거한다.
- 이번 변경: 전직 퀘스트와 일반 퀘스트를 안전하게 저장할 V007 `character_quest_progress`를 `ProjectD`에 적용했다. 기존 캐릭터는 변경하지 않으며 캐릭터·퀘스트·콘텐츠 버전별 진행·보상 완료 시점을 보존한다.
- 진행 중: 첫 실제 의뢰 `pilot1.zombie_hunt`의 저장소를 추가했다. 수락·좀비 3마리 진행·완료 경험치 100·레벨업을 하나의 DB 트랜잭션으로 확정한다. 네트워크 명령과 처치 이벤트 연결은 다음 변경에 이어진다.
- 영속 작업 원칙: 모든 구현·검증·미완료 항목은 이 파일과 `docs/CODEX_HANDOFF.md`에 즉시 기록한다. 새 세션은 이 파일의 마지막 진행 중·다음 작업·남은 문제부터 재개한다.
- 진행 중: 전직 선택의 V008 저장소·서버 검증·개발 명령은 구현됐다. 다음 완료 단위는 직업 선택 화면·서버 요청/응답·`J` 프로필 직업 표시·재접속 유지 확인이다.
- 완료: 프로필 패킷에 직업을 추가하고 통신 버전을 7로 올렸다. 서버는 저장 직업을 프로필 응답에 포함하며, `J` 화면은 직업명 또는 미전직을 표시한다. `build`가 통과했다.
- 완료: 서버 전투 런타임은 저장 직업별 주력 능력치를 사용한다. 전사=힘, 궁수=재주, 법사=도력, 도적=힘·재주 중 높은 값이며 미전직은 힘·재주·도력 중 높은 값이다. `compileJava`가 통과했다.
- 다음 작업: 서버 기력·전투 상태를 프로필 패킷과 `J` 화면에 표시하고, 기본 공격 피해에 서버 계산을 적용한다.
- 다음 작업: 직업 선택, 기본 공격의 능력치 기반 피해 적용, 기력·전투 상태 전환을 하나의 플레이 묶음으로 구현한다.
- 남은 문제: 직업 선택·무기별 기본 공격 피해 적용·기력 UI·퀘스트 수락/완료 보상은 아직 구현되지 않았고, 인게임 몬스터 처치의 최종 수동 확인이 필요하다.
2026-09-26 02:40 +09:00 — 기력 상태 패킷을 추가해 프로토콜을 10으로 올렸고, 선택한 캐릭터의 주력 능력치 배율을 서버 공격 피해 속성에 적용했다. 손에 든 바닐라 무기의 기본 공격력을 그대로 기준으로 하며, 선택·능력치 변경·초기화·전직·프로필 갱신 때 같은 식별자의 수정자로 교체한다.
2026-09-26 03:00 +09:00 — 프로필의 최대 생명력을 서버 플레이어 속성에 적용했다. 기존 최대 생명력 대비 현재 비율을 유지하고, 선택·능력치 변경·초기화·전직·프로필 갱신의 공통 전투 갱신 경로를 사용한다.
2026-09-26 03:10 +09:00 — 유효 피해의 가해자와 피격 플레이어 모두 전투 상태로 전환하게 했다. 미전투 초기 상태의 시간값을 명시적으로 제외해 접속 직후 잘못 전투 중으로 표시되거나 회복 판정이 멈추지 않게 했다.
2026-09-27 — 전사 횡참을 `R` 키 요청으로 추가했다. 서버는 활성 전사·기력 20·4.5초 재사용 대기를 검증하고, 3블록 내 시야 확보 대상에 기본 공격력의 110% 피해를 적용한다.
2026-09-27 — 전투 기술 판정은 선택·투자·초기화·전직 때 갱신되는 접속 중 프로필을 사용하도록 바꿨다. 횡참 사용 중 PostgreSQL 동기 조회를 제거했다.
2026-09-27 — 횡참 실패 시 기력 부족 또는 재사용 대기 문구를 서버가 표시하도록 추가했다.
2026-09-27 — 횡참 성공 시 주무기 휘두르기와 횡참 파티클을 서버에서 전송하도록 추가했다.
2026-09-27 — 횡참 범위를 3블록 전방 부채꼴로 제한해 플레이어 뒤 대상은 타격하지 않게 했다.
2026-09-27 — 세션 인계: 3단계 완료 조건과 누락 항목을 `docs/CODEX_HANDOFF.md`에 기록했다. 다음 구현은 전직 최초 보상 공통 기술점 6점의 V009 영속화다.
2026-09-27 — V009로 캐릭터 미사용 기술점을 추가했다. 최초 전직을 삽입한 트랜잭션에서만 공통 기술점 6점을 지급하며, 재요청은 기존 직업만 반환해 중복 지급하지 않는다. 프로필 패킷·`J` 화면에 기술점을 표시하고 통신 버전은 12다. `compileJava compileClientJava testClasses --no-daemon`이 통과했으며, V009 적용 뒤 `verifyCharacterProfession` 실제 DB 검증이 남았다.
2026-09-27 — V010으로 기술 랭크·습득 요청을 저장하고, 전직 직업·활성 캐릭터·연결 세대·요청 멱등성·기술점·5단계 상한을 서버가 검사한다. `J` 기술 습득 화면은 네 직업의 공통 6개 기술과 저장 랭크를 표시한다. 횡참은 습득 뒤에만 `R`로 사용할 수 있고 랭크 1~5에 따라 110%~150% 피해를 적용한다. V009·V010 적용과 전직 기술점·기술 습득 멱등성 DB 검증을 통과했다. 통신 버전은 13이다.
2026-09-27 — 4단계를 직업 공통 액티브·연출 확장으로 정의했다. 서버 판정·습득 랭크·기력·재사용 대기와 확정 뒤의 모션·파티클·사운드 피드백을 공통 제약으로 둔다. 전사 진격·승룡격·기합부터 횡참 경로를 확장한다.
2026-09-27 — 4단계 첫 구현으로 전사 진격을 `V` 키에 추가했다. 서버는 전사·진격 습득 랭크·기력 20·7초 재사용 대기와 전방 3블록 단일 대상을 확인하고, 랭크 1~5의 85%~115% 피해를 확정한다. 성공 시 대시·휘두르기·치명 파티클·타격음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 전사 승룡격을 `B` 키에 추가했다. 서버는 습득 랭크·기력 30·9초 재사용 대기와 전방 3블록 대상을 확인하고 랭크 1~5의 125%~170% 피해와 상승 효과를 확정한다. 성공 시 휘두르기·상승 치명 파티클·치명타음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 전사 기합을 `N` 키에 추가했다. 서버는 습득 랭크·기력 25·18초 재사용 대기를 확인하고 둔화를 해제한 뒤 랭크 1~5에 따라 저항 효과를 3~7초 적용한다. 성공 시 분노 파티클과 포효음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 궁수 관통 사격을 `G` 키에 추가했다. 서버는 궁수·습득 랭크·기력 22·5초 재사용 대기와 12블록 직선 대상을 확인하고 랭크 1~5의 115%~155% 피해를 확정한다. 성공 시 직선 화살 파티클과 발사음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 법사 영부탄을 `C` 키에 추가했다. 서버는 법사·습득 랭크·기력 20·4초 재사용 대기와 10블록 직선 대상을 확인하고 랭크 1~5의 115%~155% 피해를 확정한다. 성공 시 부적 궤적 파티클과 주문 시전음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 도적 급습을 `X` 키에 추가했다. 서버는 도적·습득 랭크·기력 22·5초 재사용 대기와 전방 3블록 단일 대상을 확인하고 랭크 1~5의 115%~155% 피해와 측후방 적중 20% 보너스를 확정한다. 성공 시 돌진·휘두르기·연막 파티클·치명타음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 궁수 후퇴 사격을 `Z` 키에 추가했다. 서버는 궁수·습득 랭크·기력 18·7초 재사용 대기와 전방 10블록 단일 대상을 확인하고 랭크 1~5의 85%~115% 피해를 확정한다. 성공 시 후방 이동·구름 파티클·발사음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 법사 속박부를 `H` 키에 추가했다. 서버는 법사·습득 랭크·기력 30·12초 재사용 대기와 전방 10블록 단일 대상을 확인하고 랭크 1~5의 70%~105% 피해와 2~3초 둔화를 확정한다. 성공 시 부적 파티클·주문 시전음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 도적 연막탄을 `Y` 키에 추가했다. 서버는 도적·습득 랭크·기력 25·16초 재사용 대기를 확인하고 5블록 안 일반 적에게 랭크 1~5의 2.5~4초 실명을 적용한다. 성공 시 대형 연막 파티클·소화음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 전직한 캐릭터는 남은 기술점이 0이어도 `J` 기술 습득 화면을 열어 저장된 기술 랭크를 확인할 수 있게 했다. `compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 도적 족쇄격을 `M` 키에 추가했다. 서버는 도적·습득 랭크·기력 20·7초 재사용 대기와 전방 3블록 단일 대상을 확인하고 랭크 1~5의 90%~130% 피해와 2~3초 둔화를 확정한다. 성공 시 족쇄 타격 파티클·사슬음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 도적 긴급 이탈을 `I` 키에 추가했다. 서버는 도적·습득 여부·기력 20·12초 재사용 대기를 확인하고 후방 이동과 둔화 해제를 확정한다. 성공 시 구름 파티클·순간이동음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 궁수 추적 표식을 `P` 키에 추가했다. 서버는 궁수·습득 랭크·기력 20·12초 재사용 대기와 전방 12블록 단일 대상을 확인하고 랭크 1~5에 따라 5~9초 글로우를 적용한다. 성공 시 글로우 파티클·화살 적중음을 재생한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 — 4단계 공통 액티브를 완료했다. 궁수 속박 사격은 `L` 키로 랭크 70~100% 피해·1.5~2.5초 둔화를, 법사 호신부는 `F` 키로 기력 35·14초 대기 뒤 흡수 보호막을, 축지는 `T` 키로 기력 25·12초 대기 뒤 전방 이동을 서버에서 확정한다. 모든 공통 액티브는 직업·습득 랭크·기력·재사용 대기와 대상/이동 조건을 서버가 확인하고 파티클·사운드를 재생한다. 통신 버전은 25다.
2026-09-27 — 5단계를 시작했다. V011에 캐릭터별 9칸 장비와 강화 요청 기록을 추가하고, `EquipmentRepository`가 활성 캐릭터 소유 장비의 +15 상한·실패 누적 확률·요청 멱등성을 트랜잭션으로 확정한다. 강화 규칙 검증과 `compileJava`가 통과했다.
2026-09-27 — 강화 요청·응답 통신을 추가했다. 서버는 활성 캐릭터·장비 소유권·요구 레벨·직업·+15 상한과 요청 멱등성을 확인한 뒤 강화 결과만 응답한다. `compileJava compileClientJava --no-daemon`이 통과했다.
2026-09-27 5단계를 완료했다. V011 장비·강화 테이블, 9칸 귀속 기본 장비, +15 상한과 실패 누적 성공 보정, 요청 멱등·활성 캐릭터 소유권·요구 조건 검증을 서버 DB에 구현했다. E 장비 화면에서 장비 목록과 강화 요청을 연결했고 통신 버전은 26이다.
2026-09-27 6단계 폐봉수대 공략 예약을 완료했다. V012는 공략·참가자·캐릭터별 주기 난이도 클리어·요청 기록을 저장한다. 서버는 초행/수련 레벨·개방·주간 3회·활성 공략 중복을 검증하고 성공만 횟수를 확정한다. F6 난이도 화면과 통신 버전 27을 연결했다.
2026-09-27 7단계 진행: 폐봉수대 입장 승인 뒤 서버 런타임이 참가자와 공략 ID를 연결하고, 공략 ID에서 계산한 분리 좌표의 9×9 발판으로 참가자를 이동시킨다. `compileJava --no-daemon` 통과. 보스·기믹·사망/종료 처리는 계속 구현한다.
2026-09-27 7단계 진행: 초행 5회·수련 3회 부활 횟수를 서버 `RunRuntime`이 보유한다. 공략 중 사망한 참가자가 재생성되면 같은 공략 발판으로 복귀하고 생명력 35%를 적용한다. `compileJava compileClientJava --no-daemon` 통과. 전멸 판정·보스/화로·클리어 월드 트리거는 계속 구현한다.
2026-09-27 7단계 완료: 폐봉수대 ACTIVE 공략은 재접속 때 DB에서 복원해 같은 공략 발판에 다시 연결한다. 서버 런타임은 입장 좌표, 난이도별 부활 횟수, 마지막 부활 소진 뒤 DB 실패 확정 기반을 소유한다. 보스·화로 콘텐츠는 다음 확장 단계다.
2026-09-27 8단계 진행: 폐봉수대 입장 시 철문수장 좀비 보스를 서버가 생성한다. 처치 뒤 봉수로 화로 3개를 생성하고 4초 정화 뒤 원혼봉수장을 생성한다. 최종 보스 처치는 서버가 공략 완료·주간 횟수 확정과 경험치 500 보상을 처리한다. `compileJava compileClientJava --no-daemon` 통과.
2026-09-27 8단계 완료: 철문수장·화로·원혼봉수장의 서버 공략 상태를 구현했다. 화로는 반경 내 4초 유지, 1/2인 1명·3/4인 2명 동시 정화로 판정하고 수련 보스 체력 1.38배를 적용한다. 최종 보스 처치는 DB 공략 완료·주간 한도·경험치 500 보상을 한 서버 경로로 확정한다.
2026-09-27 9단계 완료: `/party invite <플레이어>`·`/party accept`·`/party leave`로 최대 4명 준비 파티를 구성한다. 방장만 공략을 시작하며, 서버가 온라인 파티원의 활성 캐릭터를 고정 명단으로 예약하고 같은 공략 ID·좌표에 입장시킨다.
2026-09-27 10단계를 완료했습니다. V013 보상·보관함과 V014 전 원장을 추가했습니다. 공략 성공은 같은 DB 트랜잭션에서 파티 전원에게 초행/수련별 전·연철석·봉수패를 지급하고, 난이도별 최초 완료 때만 장비 선택 상자를 보관함 이벤트로 생성합니다. 완료 재전송은 공략 보상·원장·보관함을 중복 반영하지 않으며, 온라인 참가자 전원에게 경험치 500과 프로필 갱신을 전송합니다.
2026-09-27 11단계를 완료했습니다. V015로 공략 45분 기한과 5분 정지 만료를 저장합니다. 마지막 참가자 접속 종료는 PAUSED로 전환하며, 재접속의 기존 공략 복원 경로가 자동 재개합니다. 개인 부활 소진은 파티 전멸일 때만 실패를 확정합니다.
2026-09-27 12단계를 완료했습니다. 장비 재련은 5등급 확률과 실패 보정을 저장하고, 전 차감·원장을 같은 트랜잭션으로 처리합니다. 보관 장비를 원본으로 하는 계승은 동일 품목·레벨 조건에서 원본을 소모하고 강화·기존 크레딧의 70%를 대상 전용 잔액으로 이전하며 요청 재전송을 막습니다.
2026-09-27 13단계를 완료했습니다. V013~V020 마이그레이션과 장비·공략·보상·파티 검증을 전체 실행했습니다.

2026-10-08 MDF-3 추가 확인(worker-oscar-main): 기존 Java 검증 3개 exit 0을 재사용(소스/산출물 이후 변경 없음). PostgreSQL select 1을 직접 재확인해 exit 0. 실행 중 Java 프로세스 0개이며 실제 F6 플레이는 미검증입니다. 소스 수정 없음. 근거: verification-mdf3/OSCAR_CURRENT_REVIEW.md.


2026-10-08 F6 자동 플레이 검증: BLOCKED. F6 → RunEnterRequest → RunRepository.enter → RunRuntime.enter 경로를 정적으로 확인했습니다. 봇 패키지 다운로드는 EACCES, 오프라인 Gradle은 Loom 플러그인 미발견, 직접 Fabric 서버 실행은 프로젝트 경로 정규화 AccessDeniedException으로 막혔습니다. 실제 맵·보스·보상·주간 횟수 검증과 DB fixture 생성은 미실행이며 게임 코드·원본 서버 설정은 변경하지 않았습니다. 자체 실행 프로세스의 정리/조회도 접근 거부되어 잔존 여부 확인이 필요합니다. 증거: verification-mdf3/F6_AUTOPLAY.md.
2026-10-08 F6 실서버 1인 루프 검증(worker-oscar-f6h): 입장·일시정지/재개·부활·실패(FAILED, 횟수 0)·화로·완료(COMPLETED, 횟수 1, 보상 1건) 확인. 결함 수정 4건: 속도 제한 초기값 오버플로, 치명 사망 시 부활/실패 미처리(AFTER_DEATH), 재생성 시 프로필 수정치 소실, 보스 디스폰. 보스 처치는 바닐라 피해·HP 상한(1024)으로 1인 실전 불가해 서버측 검증. `.gitignore`의 `run/`이 run 소스 패키지를 무시하는 문제 미해결. 근거: verification-mdf3/F6_AUTOPLAY.md.


2026-10-08 MDF-5 완료: `.gitignore`를 `/run/`으로 좁혀 Java run 패키지 10개를 수정 브랜치에서 추적했습니다. 구 달마루 JAR은 보관하고 개발 실행 래퍼에 기동 전 차단을 추가했습니다. TRAINING 실제 패킷 입장·4번째 사망 실패(count 0/보상0)·완료 DB(count 1, 100전/5석/2패/20레벨 상자1)를 확인했습니다. 보스 처치 2회는 서버측 검증이며 실전 처치 PASS는 아닙니다. 봇/서버 exit 0, 자체 Gradle 데몬 종료·잔존 Java 0. 수정 커밋 94a3406819ec38e202bc8b1999fc4739bcfe6858 / oscar/mdf5-runtime-training-20261008. 기존 main HEAD/공유 스테이징 보존. 상세: verification-mdf3/MDF5.md.

- 2026-10-08 MDF-5: 승인 브랜치를 로컬 main에 --no-ff 병합(2ca4aa9bc62af65bdf6d2a8c2dff15c7b5695bd2), 충돌 없음. 기존 작업본/스테이징 보존; origin push 미수행. 기존 server-project/.gitignore·README.md 스테이징은 HEAD와 달라 후속 커밋 전 검토 필요.

- 2026-10-08 작업 트리 정리: 기존 문서(5ab289b)·서버와 autoplay 소스(bdea474)를 main에 기록했습니다. MDF-5 /run/ 규칙과 개발 실행 안내는 유지하고 실행 로그·로컬 산출물은 추적 제외했습니다. 파일 삭제·push·서버 기동은 하지 않았습니다. TEST_RESULT.md 원문은 기존 자격증명 기록 때문에 미커밋으로 보존합니다.
- 다음 작업 추천: CTP-001 5절의 1인 초행 수동 완주를 막는 전투 피해·보스 체력 상한을 먼저 확인하고, 우회 명령 없는 전투부터 상자 개방까지 검증합니다.

- 2026-10-08 MDF-8: 보스 설계 체력의 바닐라 상한 적용, 바닐라 피해 경로와 RPG 공식 미연결, 실제 장비 공격력/방어력 정의 부재를 확인했습니다. 새 브랜치 oscar/mdf8-solo-clear-20261008에 진단을 기록합니다. 기존 계산기·15레벨 수치 대조 PASS(740HP, 피해52, 보스208/277타, 초당1타 가정485초). 제품 코드는 변경하지 않았으며 설계 유지안/바닐라 수치 조정안 중 선택을 Changsik에게 요청합니다. 실제 완주 미검증. 상세: verification-mdf3/MDF8.md.

- 2026-10-08 MDF-8 A안 기반: 실제 EQUIPPED 조회, 명시적 10/15/20레벨 장비 수치와 능력치 예산, 1인용 RPG/엔진 단위 어댑터를 구현했습니다. 자동 지급 없음. 소유권/슬롯/레벨/740HP/피해52/208·277타/보스 체력 왕복 독립 Java assertions PASS. 전체 피해 경로 연결과 엔진 검증은 진행 중입니다.

- 2026-10-08 MDF-8 A안 완료: 실제 장착 장비·RPG 체력/피해·기본 공격/기술/보스 경로 연결. 컴파일·장비/계산 assertions PASS, 서버측 체력 경계 540/720/993.6 및 피해 2.6 PASS. 서버 exit 0·Java 0·설정값 보존 확인. 실제 완주·다인·회복/보호막 미검증, Jim QA·Creed 리뷰 대기. 상세: verification-mdf3/MDF8.md.

- 2026-10-08 MDF-8 보안 후속: tools/autoplay/server-run.js의 DB 비밀번호 폴백 제거. DALMARU_DB_PASSWORD 미설정/빈 값이면 로그 생성·서버 실행 전에 명확한 오류로 종료. node tools/autoplay/server-run.test.js PASS(exit 0): 기존 JAR 차단 및 환경변수 누락/빈 값 검증, 실제 서버 미기동. 이력 재작성·push 없음; 과거 이력 처리는 사람 결정 대기.

- 2026-10-09 MDF-8 F1 수정: 장비 DB 응답을 서버 스레드에서 현재 UUID 플레이어에 적용하여 부활 전 entity 갱신을 방지합니다. 기존 세션·캐릭터·revision 검증 유지. compileJava/compileClientJava/testClasses/verifyEquipmentRespawn PASS; 기존 콜백은 재현 시험 실패, 수정 콜백은 새 entity에 적용·HP 비율 35% 유지 PASS(서버/전투 대역 사용, 실제 부활 미검증). TEST_RESULT.md 불변. e HEAD 삭제; 이력 정리는 원격 문서의 동일 문자열 때문에 god 지시로 보류.

- 2026-10-09 MDF-8 push 완료: 원격 작업 브랜치 99849859b270bb6984d5379275ce02395353a871 확인. autoplay 실서버 검증은 DALMARU_DB_PASSWORD 환경변수 없음으로 BLOCKED, 서버/봇 미기동·god에 제공 요청. 결과는 verification-mdf3/MDF8.md 작업본에 기록.

- 2026-10-09 MDF-8 autoplay: 서버/봇 장착HP37·부활35% 기대값 확인, 실제 DB 지연을 가로지른 부활 후 현재 entity HP34→37, 보스HP540/720·보상1회/재전송추가0 서버측 검증. 발판 지형 겹침·질식 FAIL, 명령 없는 완주/정밀 회복은 수동 필요. 서버/봇exit0·자체Java/Node정리·설정 보존. 문서 작업본: verification-mdf3/MDF8_AUTOPLAY.md. 제품 코드/TEST_RESULT 변경·커밋·push 없음.

- 2026-10-09 MDF-8 지형 질식 수정: 지도 내부 지형을 생성 전 비우고 구조물/외부 보존. 고체 지형 회귀(기존실패/수정PASS)·컴파일PASS. 기존 봇 survival, 수동air처리 없이 새spawn74.1초·질식/사망0·Health유지 서버측 검증(보스NoAI분리). 서버/봇exit0·자체Java/Node0·설정/TEST_RESULT보존. 상세: verification-mdf3/MDF8_AUTOPLAY.md. push 없음; 캐시3개 보존·경로 보고.

- 2026-10-09 MDF-8 수동 플레이 준비: 5734da8 새 jar 빌드·서버 기동 PASS(25565 LISTEN), 지정 계정 그래픽 클라이언트 실행·준비 봇 접속/선택 PASS. 기존 15레벨 전사/장비8개 재사용, 액티브 기술4개1랭크·생존모드·초기HP37 준비. 서버는 god 종료 지시까지 유지. 정상 전투 완주는 사람 진행/미검증; 안내 hive/research/MDF8_MANUAL_PLAY.md. 제품 코드/TEST_RESULT 불변, commit/push 없음. 서버 설정 수동 변경 없음; 기동 시 날짜 주석 자동 갱신.

- 2026-10-09 MDF-8 사람 제보 후속: 프로필 버튼 넘침·입장중 중복요청/모호한 실패문구·거리 비례 보스밀침 수정. 제품 메서드 대역 회귀/컴파일 PASS; 기존 UI/밀침 assertion 실패 재현. 실제 이탈 원인 최종 확인/실전 검증 미완료. 이전 서버 유지 중이며 god에 서버·클라이언트 재시작 승인 요청. TEST_RESULT 불변, commit/push 없음. 상세 verification-mdf3/MDF8_MANUAL_FIX.md.

- 2026-10-09 MDF-8 제보 수정 반영: god 재시작 승인 후 자체 서버 exit0/클라이언트 이미 종료 확인·잔존 자체서버데몬 정리. 수정 jar 빌드 PASS, 서버32488/클라이언트32052 재기동·Minecraft 상태응답/렌더 초기화 확인. UI·입장응답·밀침 실제 재플레이는 god가 Jim/사람에게 배정. TEST_RESULT 불변, commit/push 없음.

- 2026-10-09 MDF-12 머지 전 안전 수정: O01~O04·O12~O15·C17·C21/J21·J12·J18~J20 구현. 세대/프로필 보존, 강화 비용·귀속·장착, 캐릭터별 바닐라 상태, 공략 보상XP 원자 지급, 부활/재접속/정지·만료, 이동·대상·구역 경계와 종료 복구를 수정했습니다. 컴파일·임시 PostgreSQL V001~V021 fresh migration·DB/런타임/수동제보/지형 회귀 PASS. 실제 서버 재기동·실제 DB V021 적용·던전 입장 미수행; TEST_RESULT.md 해시 보존·commit/push 없음. 마을 경계 등록, +6 이상 강화 가격 확정, Creed 리뷰/Jim 실전 QA 필요. JAR SHA256 FACF5CA7EFB2E13B819344D6093B66F7673F0C615ED77A0F35DB055885D2D66B. 상세: hive/research/MDF12_PREMERGE_FIX.md(공유 hive).
- 2026-10-09 MDF-12 리뷰 R1~R6 수정: 프로필 DB revision·역순 응답 차단, 전환 전 checkpoint/NBT 소유 표식·로그인 복구, 장비 ID 순서 변경 시 버튼 재생성, 접속/해제 DB 처리의 서버 스레드 순서 보장, 오프라인 복귀 모드 저장·NBT 저장 후 ack, 지도 생성 전 지형 파일 journal·시작 복구·잔존 개체 태그 정리. 각 R1~R6 수정 전 FAIL/후 PASS, 컴파일·신규2종/기존4종 회귀·jar PASS. V022는 임시 DB 기존 synthetic schema에만 적용; 실제 V021/V022·마을 등록·재기동 보류. TEST_RESULT 불변·commit/push 없음. 새 JAR SHA256 36F1F19AB18345226B992D1DC86A8CE49A8CC3A475F79E42DBCAB04026B96C76. 구 데이터의 표식/모드/지형 기록이 없으면 수동 복구·실전 QA 필요. 상세: 공유 hive/research/MDF12_PREMERGE_FIX.md R1~R6 절.
- 2026-10-09 MDF-12 N1/N2 수정: JOIN 복구·DISCONNECT pause·복귀 ack를 단일 별도 DB 큐로 이동, 원래 state/player/connection 일치 검사·세대 발급 FIFO·완료 전 disconnect의 pause 유지. 계정/캐릭터/공략 JDBC connect/login1초·socket2초·query250ms·lock200ms 제한. 수정 전 틱 정지/늦은 콜백/세대 역전/무제한 lock FAIL, 수정본 신규2종+기존6종·컴파일·jar PASS; 실제 임시 DB lock 거부233ms·세대불변. 소유표식 없는 구 NBT 불일치 및 구 복귀 mode NULL의 fail-closed 검사 PASS. 실제 DB 행 수는 DALMARU_DB_PASSWORD 없음으로 BLOCKED(0건 추정 금지); 실제 DB 적용·재기동·commit/push 보류, TEST_RESULT 불변. 새 JAR SHA256 2A19053ED3AF22359E500FDCC8F1C172395109735ADD829C9A99F9363E66C58B. 상세: 공유 hive/research/MDF12_PREMERGE_FIX.md N1/N2·V022 기존 데이터 절.

- 2026-10-09 MDF-12 N3/N4/N5 수정: 같은 handler의 JOIN 대기 중 부활은 현재 player로 복구, COMMIT 결과 불명은 원본 가방 복원 대신 소유 표식/가방 격리 후 접속 종료, 복귀 ACK는 정확 run/character/account/세대로 제한. 수정 전 N3/N4/N5 FAIL 재현, 수정본 기존8개+신규 boundary 검사/컴파일/jar PASS; 마지막 로깅 보완 후 boundary/jar PASS. TEST_RESULT SHA 불변, 임시 PostgreSQL 소유 경로 확인 후 종료 및 Java/Javac 잔류0. 실제 DB/서버/V021·V022/재기동/commit/push 없음. JAR SHA256 3D4D7BFFD80E037E551DE436BA2F46396642958DF8AC7CD7CBEF5BE1465C74D4. 실제 사망·격리/NBT 재접속·소켓 단절·오프라인 복귀 QA 및 기존 데이터 확인은 보류. 상세: 공유 hive/research/MDF12_PREMERGE_FIX.md N3/N4/N5 절. 코드/허용 검증 COMPLETE, 운영 적용·실전 QA 포함 전체 PARTIAL.
- 2026-10-09 MDF-12 N6 수정: 미확인 terminal 복귀가 있는 참가자는 새 입장/request replay 차단, 재접속 복귀·저장·정확 ACK 후 재시도 안내. pending의 참가자/공략 행 잠금으로 만료 경계 보호. 수정 전 임시 PostgreSQL 입장 허용 FAIL, 수정본 기존9개 검사+N6·컴파일/jar PASS, 정상/차단 replay·파티원 차단·이전 ACK의 새 pending 보존 추가 PASS. TEST_RESULT 불변, 임시 PG 종료·Java/Javac 잔류0. 실제 DB/서버/마이그레이션/재기동/commit/push 없음. JAR SHA256 1B180A3C90FEF48521ADD06EB086AC2DF38E6A5D2BCC541BB74A5D6F298E6539. 자동 온라인 ACK는 생략한 보고서 허용 대안이며 만료 뒤 재접속이 필요. 실제 게임·동시 경계 QA 남음. 공유 hive/research/MDF12_PREMERGE_FIX.md N6 절. 코드/검증 COMPLETE, 운영/실전 포함 PARTIAL.
- 2026-10-13 MDF-12 운영 적용 완료: 승인된 만료 PAUSED run 5a59a5ac-c070-4922-83fe-4ae91e81767a만 FAILED(rowcount1), 당시 주간1/대상보상0 유지. V021/V022 실DB 적용/schema22·월드스폰 마을(32,77,-64)/반경32 등록. 승인 JAR SHA 1B180A3C90FEF48521ADD06EB086AC2DF38E6A5D2BCC541BB74A5D6F298E6539 기동, 서버4936/래퍼18664·25565 유지. 지정 시험 계정만 콘솔 마을teleport/survival/save-all, 저장 NBT(32.5,77,-63.5)/overworld/mode0 확인·시험접속exit0. 진행중/NULL owner+snapshot/NULL mode pending 모두0, 시험장비8 유지(V021 귀속전환). DB백업·운영 안내: 공유 hive/research/MDF8_MANUAL_PLAY.md. 사람 캐릭터 위치/모드·주간/보상/물품 직접변경·던전입장·제품코드·설정 수동변경·commit/push 없음. TEST_RESULT불변; 서버properties 부팅 날짜 자동갱신. 적용 COMPLETE, 실전 UI/전투/장애복구 QA는 별도.
- 2026-10-13 MDF16/J02 1단계: 별도worktree/branch에서 C02 전투중기력회복, C03 허공기술타이머제거, C11 횡참랭크계수, J02 던전몹XP/필드좀비quest/vanilla loot·XP 차단 먼저 구현. compileJava/compileClientJava/verifyCombatConsistency PASS. 운영PID4936·원래JAR·운영DB 변경없음. 후속 전투효과/슬롯/방어/부활/몹구간 진행중.
- 2026-10-13 MDF16/J02 체크포인트 PARTIAL: 별도 branch/worktree에서 전투효과·슬롯/모드/READY·회피/방어/부활 초안 compileJava/compileClientJava PASS, CombatConsistency 모델/실제메서드대역 PASS, 임시DB Loadout owner/세대/replay/ACTIVE/frozen검사 PASS. V023 임시DB만, V024 파일만. J02 roster/spawn/구간·전체회귀·실제mixin/packet/안전부활/GUI 검증은 남음. breaker 작업토큰 상한 경고로 god에 진행방향 요청. 자체 임시PG 소유경로확인 후 종료, 운영PID4936만 유지/원래JAR·운영DB 불변. 상세 공유 hive/research/MDF16_J02_IMPLEMENTATION.md. 추가commit/push없음.
- 2026-10-13 MDF16/J02 구현 완료(운영 미적용): J02 6종/24+2/순차 그룹·화로·소환·cleanup, C05/06 안전바닥/안전점DB, C01 전원READY/준비UUID/세션·비동기응답 경합/취소 UI 연결. 단계 검사 PASS, 기존 회귀 6개 fixture 실패는 수정 후 해당6개 PASS, 실제 격리 Minecraft 몹·그룹·소환·cleanup/같은방 안전바닥 PASS. 최종 assemble PASS. 운영 PID4936/JAR SHA256 불변, 운영 DB 미접근, V023/V024는 임시DB만. 추가commit/push 없음. 실제 클라이언트 UI/키 입력/동시접속과 실제 피해·보상·부활 타이밍은 Jim QA 필요. 상세 hive/research/MDF16_J02_IMPLEMENTATION.md.

- 2026-10-13 후속 QA: 작은UI폭/HUD기술슬롯폭/미사용문자스킬키 제거, profile미준비입장차단. 실제발견한필드부활대기중안전점덮어쓰기는deathsnapshot+spectator대기+disconnect/종료복원으로수정. 실제client렌더/주입키라우팅·4인packet·필드3초/HP50%/기력reset검사 PASS. 관련focused회귀/assemble/diff검사 PASS. 상세 verification-mdf16/QA.md. 운영미적용/추가commit없음. 물리키·4인완주·loot/화로/던전면역플레이QA남음.

- 2026-10-13 4인통합 검증PASS: 실제4bot/서버/DB로24+2·그룹문/화로4초·2인동시/태그Zombie loot·XP·quest차단/던전35%HP·25%기력·3초면역/완료보상4개·주간1회·복귀/지형/개체cleanup. 시험damage/protection으로진행했고수동밸런스는미검증. 제품추가변경없음. 상세 verification-mdf16/FULLRUN.md. 운영/Git미조작.


- 2026-10-13 MDF16 ?? R1-R5 ??: ??? ?? ???, unloaded mob ?? ??, DB ?? ? ?? ?? ???/?? ???, ?? ranged ?? ??, ??? SLOWNESS ??? ??. `compileJava compileClientJava` ? `verifyReviewFixBoundary` PASS. R6-R9 ??? shared `MDF16_J02_IMPLEMENTATION.md`? ?? ??. ?? ??? ????? ??.


- 2026-10-13 ??? N1 ??: ?? ?? ??? ?? run? PAUSED ???? ???? ENTER request/generation/current account generation? ???? stale callback? ??? run ??? ??. ?? PG ? ??/??? ?? PASS. DB ?? ?? ??.

- N1 ??: ??? DB?? ?? ??? ???? ? ??? ??? ????? ??. ??? ?? ???? ?? ??? ? ?? ??. `verifyRunEntryCancellation` ?? PASS.

- Review3 ?? ??: ?? ??? ?? account generation? ??? ??? ?? ENTER tuple? ??. reconnect ??? ?? ?? ??? ???? ?? generation tuple? ??. ?? DB ??? ???? ??? ?? ??. ?? ??? ?? ??? PASS.
- 2026-10-13 MDF16 Review4 N1-N3: `verifyRunLifecycleOwnership`, `compileJava compileClientJava`, `git diff --check` PASS. UUID별 PREPARING/ACTIVE/CLOSING/CLOSED 수명주기 소유, 실패/종료 후 재생성 차단 및 JOIN 재접속 제한을 반영했다. ACTIVE 요청 재생은 준비 검증보다 먼저 동일 owner와 전체 현재 파티 참가자 일치를 확인해 응답하며, 거절 경로에서 기존 활성 런을 닫지 않는다. 운영 서버/DB 미접속, 배포·커밋·푸시 없음. 실전 동시 접속 및 종료 경합 QA는 별도 필요.

- 2026-10-13 MDF16 Review5 N4/N5: `verifyRunLifecycleOwnership`, `compileJava compileClientJava`, `git diff --check` PASS. JOIN restoration failures now disconnect only that participant and preserve shared run state. `closeAll` continues across per-run failures and reports accumulated errors; server shutdown independently attempts DB cancellation even when runtime cleanup reports errors. No live server/DB or deployment was used. Live multi-client and shutdown-failure QA remains outstanding.

- 2026-10-14 MDF-P1 S01/R10: field mode countdown now uses the later automatic/manual expiry; fixed town/dungeon modes show no return countdown. verifyCombatConsistency (manual entry/decay/expiry/cancel/overlap/fixed mode/old long deadline) and compileClientJava PASS; compileJava ran as dependency. No migration/protocol/layout/live-server change. Independent Codex/Creed/Jim review pending; P1 ongoing.

- 2026-10-14 MDF-P1 S02/R6: hostile same-domain monster/aggro/run entity policy now gates actual/terminal damage, protected hits and RPG target selection. Environment/passive/party/cross-run actions do not refresh combat; valid fully absorbed shield hits do. Evasive passive activation also requires a valid hostile hit. verifyCombatConsistency/compileClientJava PASS (11s, compileJava included). New real protect-body fixture exercises 8 valid/invalid guard/dodge/shield/zero cases; hostile policy checks included. No migration/protocol/live access; independent review pending.

- 2026-10-14 MDF-P1 S03/R7: field revival validates active point, same-dimension towns, other registered towns, then world entry; missing/malformed dimensions are skipped and existing safe-floor validation is retained. Character checkpoint storage remains unchanged. verifyFieldReviveBoundary/compileClientJava PASS (8s; compileJava previously passed). New real selector-body fixture covers 7 fallback paths. Updated stale boundary fixture for activeCheckpoint ownership/startPending invalidation. No migration/live access; independent review pending.

- 2026-10-14 MDF-P1 S07/J15 (independent slice advanced): run reservation week is now the Thursday date after shifting KST time by six hours. Stored run/clear/replay week IDs remain untouched. verifyRunEncounterRules/compileClientJava PASS (9s; compileJava included), 10 cutoff/next-week/year-boundary cases. No migration, DB access or live deployment; independent review pending.
- 2026-10-14 MDF-P1 S05a/C18: added stamina/cooldown/rank1-5 effect tooltips for all24 skills; maximum-rank learning buttons disabled and update on rank changes. verifySkillPresentation/compileClientJava PASS (10s). No initial grants, migration or live deployment. Slot locks remain S05b; independent QA pending.
- 2026-10-14 MDF-P1 S05b/C01: new lazy loadouts contain canonical active4 IDs without learning grants; unlearned screen/HUD slots show gray locked status. Existing rows/empty layouts retained. Update validation permits only unchanged unlearned positions; new equip/move/duplicates rejected. Character ready/selection fetches ranks. verifySkillPresentation/verifyClientScreenBounds/compileClientJava PASS (11s). No migration/live; independent QA pending.
- 2026-10-14 MDF-P1 S04a/R8/J09: server-thread vanilla respawn commands gated at100ticks; eligible dead connected dungeon players auto-request vanilla respawn at300ticks. Existing health35/stamina25/count/party failure retained; old player processing stops after replacement. verifyRunRespawnBoundary/verifyRunEncounterRules/compileClientJava PASS (6s). Initial compile failed after a support edit used the wrong cwd; corrected before successful validation. UI follows S04b, runtime Mixin/independent QA pending; no migration/live.
- 2026-10-14 MDF-P1 S04b/R8/J09: vanilla death-screen Mixin shows server revive count, click/auto countdown and spectator status; button unlock uses server state. State payload sent on death/20tick/respawn and reset on connection/alive. Development protocol29; live28 untouched. PASS verifyPremergeRuntime/verifyRunRespawnBoundary/verifyRunDeathScreen/compileClientJava (10s). Existing premerge fixture initially lacked keepRegionLoaded/entity movement/group fields; corrected alongside new auto/deathTick fixture support. Creed S04a blocker0 received; four integration risks tracked externally. No DB/migration/live/human play; independent S04b QA pending.
- 2026-10-14 MDF-P1 S06a/C19: loadout guards character/selection request floor/revision/equal-revision response order. Unrelated query does not release pending edits; lost edit stays locked until10s snapshot recovery; lost initial queries also retry. Rank responses accept latest query only, retry at100ticks, invalidate on selection. PASS verifyClientReplyRecovery/compileClientJava (10s), actual client classes. Learning mutation timeout and further small-screen UI remain S06b; no DB/protocol/live changes.
- 2026-10-14 MDF-P1 S06b/C19: lost reset/profession/learning replies retry immutable original request ID/body at100ticks; pending remains locked until matching reply. Selection cancels pending and rejects foreign profiles while request counters remain monotonic. Learning UI disables with no points/pending. PASS verifyClientReplyRecovery/verifySkillPresentation/compileClientJava (10s), actual profile state fixtures. C20 equipment/profile bounds remain S06c; no protocol/DB/live changes.
- 2026-10-14 MDF-P1 S06c/C20: minimum supported logical GUI320x180. Equipment adapts width and pages by available height, retains empty/shrinking/resize controls and action IDs. Profile compresses nine rows; death countdown also fits180px height. PASS verifyClientScreenBounds/compileClientJava (7s) and verifyRunDeathScreen/compileClientJava (8s). First bounds fixture compile failed from missing profile navigation stub, corrected. Actual human GUI resize not run; independent QA pending. No DB/protocol/live changes.
- 2026-10-14 MDF-P1 S09a/O26: enhancement results carry stored original success/enhancement/failure separately from latest equipment; replay wire values use stored scalars. Client acknowledgments only show2s notice and trigger ordered latest inventory query, never overwrite gear; selection invalidates prior gear replies. PASS verifyEnhancementReplay/verifyClientReplyRecovery/compileClientJava (9s), dedicated oscar_p1_fixture schema24. Initial fixture cleanup FK failure corrected; leftover test account cleaned, fixture accounts0. No new schema/live migration/protocol/deployment; O28 journal remains S09b, independent QA pending.
- 2026-10-14 MDF-P1 S09b/O28 phase1: UUID RequestEnvelope canonical SHA-256 body hash, authenticated account/active-character/generation journal, immutable result/event/application state. Enhancement domain/legacy adapter commits journal and resource changes together; UUID retries preserve original legacy correlation. V025 expand-only, fixture schema25 only; production/live unchanged. PASS verifyRequestJournal/verifyEnhancementReplay/compileClientJava (10s), concurrent UUID duplicate executes once, rollback/body/owner/generation/recovery-ack/legacy compatibility. V025 rerun PASS after correcting an initial relative-path error; fixture accounts0/requests0. Client UUID wire/other writers/actual world application recovery remain S09c+ and M11; O28/P1 PARTIAL, independent QA pending.
- 2026-10-14 MDF-P1 S09c/O28 wire: development protocol30 adds server generation/character context, bounded UUID OperationRequest and original-result/event/state receipt. Enhancement routes through journal; legacy endpoint retained. Client exact100tick retry/UUID-correlation-context matching/selection reset/latest inventory; uncertain DB outcome keeps same request recoverable. PASS verifyClientRequestEnvelope/verifyOperationWire/verifyRequestJournal/verifyEnhancementReplay/compileClientJava (14s). Other writers and actual world ACK/reconnect recovery remain next slices/M11. No schema change beyond fixture25, production/live untouched; running-server/human wire QA pending.
- 2026-10-14 MDF-P1 S09b review correction: god09:24 message Creed PASS/codex minor1. Reproduced confirmed-result Gson equality collapsing9223372036854775806/9223372036854775807 (FAIL), switched to exact normalized JSON string comparison using existing BigDecimal canonicalization. New single regression proves changed large integer rejects without SQL; PASS journal result/journal/enhancement/compile (13s). Separate corrective commit; no schema/protocol/live change. Other economic writers not yet migrated, independent fix verification pending.

## 2026-10-14 MDF-P1 S10a
- Versioned immutable JSON catalog consumed by equipment69 templates, tier bonuses, enhancement prices/chances/pity/scaling, and existing zombie quest goal/XP. Startup rejects invalid definitions; current numerical values preserved.
- Quest version pilot-1 retained to prevent reward re-grants; bundle/run manifest pilot-j02-1. Disabled equipment/quests blocked. Quest status now reads configured goal.
- PASS content catalog/enhancement rules/server-client compile (11s), diff check. No migration/protocol/deployment/production DB changes.
- O27 remains PARTIAL: skill refs use existing runtime capability; full skill definitions/display metadata, run snapshot and J02 mob data remain subsequent slices. Independent Creed/Jim review and human play pending.

## 2026-10-14 MDF-P1 S10b1
- New database reservations persist catalog version and immutable JSON snapshot. Archived reader checks ID/version/manifest/activation; unknown legacy snapshot rejects rather than substituting current definitions.
- V012 already has content_version NOT NULL DEFAULT pilot-1 (earlier summary was incorrect). V026 preserves that label and NULL legacy snapshots, adds snapshot and immutable ID/version/snapshot trigger; no backfill/contraction.
- PASS fixture reservation/replay/archived read/immutability/state update/missing and mismatch rejection/legacy preservation/migration rerun plus compile (31s). oscar_p1_fixture schema26 only; production unchanged.
- Runtime snapshot consumption/J02 mob data are S10b2; M10/O27 and independent QA remain PARTIAL.

## 2026-10-14 MDF-P1 S10b2
- J02 six mob archetypes/24 roster/party scales/boss HP now versioned JSON; entry loads the stored DB catalog on the lifecycle executor and pins it in Encounter before world mutations. Group/spawn/defense/attack/boss consumers read that snapshot; changed snapshot on existing encounter rejects.
- Preserve old numeric values/AI archetype behavior/loot and XP rules. Immutable deep rosters; invalid kinds, disabled refs, zero timing/scales/boss HP and commander placement rejected.
- S10b1 Creed PASS received. Review condition handled: resume safely fails legacy ACTIVE/PAUSED with snapshot NULL, retains return checkpoint and prior rewards; terminal records do not need frozenContent. No guessed definitions/backfill.
- PASS catalog24 invalid inputs/current and archived values, J02 roster/scaling, actual runtime lifecycle/pin/change rejection, DB legacy return/ACK/finished record/reward preservation and migration rerun, server/client compile. Fixture accounts0/runs0/schema26.
- No new migration/protocol/live change. O27 full skill/display definitions and broader per-run equipment/skill definition consumers remain; M10/P1 PARTIAL pending remaining scope and independent QA. Missing legacy return mode remains pre-existing manual recovery requirement; no invented return mode/coordinates.

## 2026-10-14 MDF-P1 S10c1
-24 validated skill definitions now contain display, content/schema revision, executable profession/kind, weapon profiles, fixed pilot unlock/rank policy and cast energy/cooldown. Canonical JSON order preserves default active4 slots.
- SkillDefinition API reads catalog names/descriptions/kinds;16 actual cast methods read costs/cooldowns without changing checks/map ownership or existing numbers.
- Content schema2 adds full metadata; schema1 pilot-j02-1 snapshots resolve fixed schema1-skills.json baseline rather than newer display/tuning. Old resources/data preserved; unknown schema1 revision rejects.
- PASS skill metadata/schema1/12 invalid inputs/16 actual cost-cooldown boundaries, presentation24, catalog, combat/runtime/DB snapshot regressions and compile. No DB migration/protocol/live change.
- O27 effects and wider display/per-run routing remain S10c2+; O28/M08/M11-M14 follow. P1 PARTIAL, independent QA requested. S10b1/S10b2 Creed and Jim group5 PASS received.

## 2026-10-14 MDF-P1 O27 duration correction
- Before effect-definition extraction, found actual rank5 smoke90ticks vs specification80ticks and shackle80ticks vs specification60ticks. Corrected only duration interpolation; retain existing target filters/control mechanism/amplifier and binding skill behavior.
- PASS combat consistency/server-client compile23s and diff check. Endpoints smoke50/80 and shackle40/60 plus timed control expiry tested; live vanilla event/GUI not tested.
- O27 definitions/per-run consumers and later P1 stages still in progress. Separate correction commit, no DB/protocol/deployment change.

## 2026-10-14 MDF-P1 S10c2
-24 skill effect endpoint tables now validated schema3 content: damage/passive bonuses/health/stamina/shield/control/mark/movement values consumed by existing handlers. Geometry, target filters, immunity checks and ability kinds retained; no interpreter/new abilities.
- schema1 reference and schema2 metadata snapshots retain fixed historical effects (smoke90/shackle80 at rank5); new schema3 uses corrected80/60. Existing metadata/numerical contracts preserved except prior separate duration correction.
- Marks capture bonus/duration at cast rather than recalculating newer tuning. Shield API accepts explicit lifetime while old160tick adapter remains. Parameter maps immutable and capability keys exact; negative/decreasing/invalid timing/percent/movement values reject.
- PASS skill metadata/effects/schema1-2,16 actual casts, catalog24 rejection cases, actual combat/protection/runtime/UI/DB snapshot gates and compile32s. No migration/protocol/production changes.
- Per-run combat/equipment definition routing and other display metadata remain S10c3+ before O28. Missing posture model remains existing design limitation: rising strike preserves current knock-up behavior; no unrequested model fabricated. P1 PARTIAL/QA pending.

## 2026-10-14 MDF-P1 S10c3
- Combat catalog provider resolves the player's owned RunRuntime Encounter; outside run current catalog, missing mapped Encounter fails closed. Same pinned object serves cast/effects and equipment stats; no second mutable content cache.
- PilotEquipment overload consumes supplied item/bonus/enhancement tables. Existing party entry installs all runtime owners then refreshes profiles with existing health/stamina-ratio preservation; field equip API remains current catalog.
- PASS actual provider-backed16 casts, archived energy21 vs current20, actual run pin/changed rejection/closed fallback, archived item+bonus+scale stats77/6 vs current69/4, combat/runtime/catalog/DB gates and compile26s.
- No migration/protocol/production changes. Remaining O27 common display metadata/validation before O28; P1 PARTIAL and independent QA pending. Live world/profile attribute installation not tested.

## 2026-10-15 MDF-P1 S10c4
- Common ID/schema/revision/display/enabled metadata validated for bundle/equipment templates/quest/run/mob/enhancement;69 generated equipment labels and quest/run UI use catalog display. Unknown/legacy item IDs retain original labels.
- schema4 expands metadata while schemas1-3 retain old display behavior. New content revision pilot-j02-2 and corrected smoke/shackle skill revisions pilot-2 distinguish changed definitions from historical pilot-j02-1; legacy rows/snapshots untouched and quest pilot-1 retained.
- Reject duplicate/missing/blank/mismatched metadata, unsupported critical-table/part disable and skill weapon subset unsupported by current handlers. GUI quest fixed width360 overflow at320x180 replaced with existing adaptive-width pattern.
- PASS definitions30 invalid cases/skill metadata-effects-archive/server-client compile, DB version/archive/immutability, actual five screen layouts320x180~960x540. Fixture accounts0/runs0.
- O27 code slices complete for assigned J02 definition/version/display scope; independent QA pending. O28 next, then M08/M11/M12/M13/M14. Whole P1 PARTIAL; existing posture-model limitation recorded separately.

## 2026-10-15 MDF-P1 S09d1
- Skill learning uses common UUID/hash journal in the same point/rank/legacy-request transaction. Old long API adapts deterministically; changed correlation reuses stored original legacy ID. Receipt/original rank separate from coherent latest profile+ranks under existing profile lock.
- Actual stored profession/level/skill-point/max-rank checks authoritative; cached profession cannot grant foreign-class skills. Legacy server response merges only increasing selected-character ranks, preventing old replay/reverse response rollback without coupling to unrelated profile updates.
- PASS actual fixture UUID/legacy/concurrent charge-once/original-vs-latest/body/generation/profession/level/after-debit SQL failure rollback, exact response-cache helper including unrelated profile update. Final accounts0/requests0; compile and large-integer journal regression PASS.
- No new SQL/protocol/live changes. Skill UUID wire/client retry follows S09d2; other mutation writers and actual application ACK/recovery remain. Merge shortcut requires revisioned snapshots if future rank reset/revoke is added; current pilot has no such writer. P1 PARTIAL/QA pending.

## 2026-10-15 MDF-P1 S09d2
- learn_skill now uses existing generic UUID operation wire. Legacy receiver delegates same helper; server derives executable profession then validates DB and sends latest monotonic profile before original-outcome receipt.
- Client common tracker exclusively retries same envelope; typed skill retry delegated by clearing its request object. Missing context cancels pending UI; completion correlates ID and does not install original rank, querying latest ranks/loadout instead.
- PASS actual receipt replay/reject/malformed/wrong skill/noninteger/rank6/correlation parser, actual typed retry delegation, generic retry/context/selection and backend journal gates+compile19s. No new packet format/protocol/migration/live change.
- Other O28 writers and actual event ACK/reconnect recovery remain. P1 PARTIAL and independent QA pending. Prior S09d1 commit e165726; this wire slice follows it.

## MDF-P1 S09e1 (2026-10-15)
- O28 능력치 투자: UUID 봉투·저널을 기존 long 호환 경로와 같은 트랜잭션에 연결했습니다. 재생은 저장 원결과와 최신 프로필을 구분합니다. 과거 long 요청은 저장 스냅샷으로 저널을 생성하고 추가 차감하지 않습니다.
- 능력치 공통 wire는 다음 조각입니다. S09d1/e165726·S09d2/baff52b Creed+Jim PASS 통보 확인. 전체 P1은 PARTIAL입니다.

## MDF-P1 S09e2 (2026-10-15)
- 능력치 투자 common UUID wire 연결. 서버는 정수 본문/1점 투자/현재 세대·선택 캐릭터 검증 후 최신 프로필 먼저, 저장 원결과 receipt 나중 전송합니다. 불확실 실패는 같은 UUID 재시도를 유지합니다.
- 클라이언트는 기존 5초 대기 해제로 새 요청을 허용하는 경로를 common 요청 중 비활성화했습니다. 원결과를 프로필에 덮어쓰지 않습니다. M11 durable ACK/reconnect는 남습니다.

## MDF-P1 S09e3 (2026-10-15)
- 능력치 초기화 common UUID 저널·long 호환 연결. 초기화·환급·무료 사용 소모·저널 확정이 같은 트랜잭션이며, 원결과와 최신 프로필을 분리합니다. 과거 초기화 요청은 저장 스냅샷을 채택합니다.
- 초기화 wire/전직 및 나머지 O28 writers, M08/M11/M12/M13/M14는 남습니다. 전체 PARTIAL.

## MDF-P1 S09e4 (2026-10-15)
- 초기화 common UUID wire·기존 typed adapter 연결. 서버는 빈 본문/현재 캐릭터 검증 뒤 최신 프로필 먼저, 저장 원결과 receipt 나중 전송합니다. 불확실 실패는 동일 UUID 재시도 유지.
- 클라이언트는 typed 재시도를 common에 넘겨 중복 재시도를 막습니다. 원결과는 안내에만 사용하며 최신 프로필을 덮어쓰지 않습니다. 전직·나머지 writers/M08/M11/M12/M13/M14는 남습니다.

## MDF-P1 S09f1 (2026-10-15)
- 전직 선택 common UUID 저널·long 호환 연결. 직업 저장/초기 기술점 6점 지급/원결과·저널 확정이 같은 트랜잭션이며, 반복 UUID는 저장 직업과 최신 프로필을 반환합니다. 레벨10·의뢰 보상 선행조건 및 이미 선택한 직업 변경 금지를 유지합니다.
- 전직 wire/나머지 O28 및 M08/M11/M12/M13/M14는 남습니다. 전체 PARTIAL.

## MDF-P1 S09f2 (2026-10-15)
- 전직 common UUID wire·기존 typed adapter 연결. 현재 세대/선택 캐릭터·본문 검증, 최신 프로필 먼저·저장 직업 receipt 나중 전송. 불확실 실패는 동일 UUID 재시도를 유지합니다. 클라이언트 typed 재시도는 common에 위임하고 원결과로 최신 상태를 덮어쓰지 않습니다.
- 개발 전직 명령도 journaled 결과의 최신 프로필을 같은 세션에 반영합니다. S09e1~e4 Creed+Jim PASS/S09f1 Creed PASS 통보 확인; S09f1 Jim 및 S09f2 독립 QA는 별도 확인 필요.
- 나머지 O28 writers/M08/M11/M12/M13/M14 남음. 전체 PARTIAL.

## MDF-P1 S09g1 (2026-10-15)
- 기술 장착 변경 common UUID 저널/long 호환 연결. 저장 원래 revision·slots와 최신 장착 상태를 분리합니다. 변경 후 재생 및 ACTIVE 공략 중의 읽기 전용 재생을 허용하며 새 변경의 버전·직업·기술종류·중복·공략 잠금은 유지합니다. 과거 요청은 저장 revision-1과 slots로 본문을 검증해 채택합니다.
- 분담 변경: god 통보에 따라 그록이 별도 Dalmaru-mdf-p1-grok/grok/mdf-p1-m08(631e06e 기준)에서 M08 담당. Oscar는 나머지 O28 → M11 → M12 → M13 → M14 순서. M08은 양 게이트 통과 후 합칩니다. 장착 wire는 다음 조각입니다. 전체 PARTIAL.

## MDF-P1 S09g2 (2026-10-15)
- 기술 장착 common UUID router/client·typed adapter 연결. revision은 정확한 long 정수, 슬롯은 문자열 5개로 검증합니다. 서버는 최신 목록 먼저/저장 원결과 receipt 나중 전송하며 불확실 실패는 같은 UUID로 재시도합니다.
- 월드 편집 잠금은 새 쓰기만 거부하고 저장 결과 재생은 허용합니다. client 기존 대기 만료 조회를 common 중 위임해 단일 재시도하고, 원결과는 적용하지 않고 최신 목록을 조회합니다. 파티 준비 해제는 실제 새 변경 확정 때만 수행합니다.
- M08 별도 작업과 같은 DalmaruNetworking 파일을 사용하나 장착 router/updateLoadout 영역만 수정했습니다. 나머지 O28/M11/M12/M13/M14와 M08 합치기 남음. 전체 PARTIAL.

## MDF-P1 S09h1 (2026-10-15)
- 장비 이동 UUID 저널/long adapter 연결. 저장된 원래 장비ID·목표 슬롯과 최신 장비 목록을 분리하며 목록도 같은 트랜잭션에서 읽습니다. 장비 소유권과 장착 조건, 슬롯 교체·귀속·이력·저널 원자성을 유지합니다. 월드 잠금은 새 쓰기만 막고 확정 재생은 허용합니다.
- S09g1/g2 Creed+Jim PASS 통보 확인. 그록 분담 요청에 M13a 시험DB 백업·복원 훈련(scripts/m13-fixture-recovery.ps1/docs/M13_RECOVERY_EVIDENCE.md, 기준1c1523a)을 제안했습니다. M08은 추가 확인 뒤 별도 합치기 지시 대기. 장비 이동 wire/나머지 O28/M11/M12/M13b/M14 남음. 전체 PARTIAL.

## MDF-P1 S09h2 (2026-10-15)
- 장비 이동 common UUID router/client 전송과 기존 명령 adapter 연결. 장비ID 문자열/equip boolean 본문·현재 세대·소유권 검증, 최신 목록 먼저·원슬롯 receipt 나중 전송. 불확실 저장 실패는 같은UUID 재시도 유지, 저장 후 월드 반영 오류는 확정 결과를 거부로 바꾸지 않습니다.
- 전투/공략/입장처리 중 새 이동은 거부하고 저장 결과는 읽기재생합니다. 편집 가능 상태의 replay도 최신 장비로 전투 상태를 갱신합니다. client는 원슬롯을 적용하지 않고 최신 목록을 조회하며 거부 안내도 표시합니다. M11 실제 world ACK/reconnect는 남습니다.
## MDF-P1 M08 (2026-10-15)
- 접속이 끊긴 참가자는 10초 동안 생존자로 남아 전멸을 막고, 공략은 그 창이 끝난 뒤에만 정지합니다. 재접속은 기존처럼 정지를 해제하고 남은 부활 수를 유지합니다.
- 끊긴 자리에는 공격력 0인 허스크 잔류 개체를 두고, 던전 몹·보스가 사거리 안이면 저장돼 있는 방어력으로 피해를 넣습니다. 그 개체가 죽으면 부재 사망으로 전멸을 계산합니다. 재접속·창 종료·사망 때 개체를 제거합니다.
- 운영 DB의 즉시 PAUSED와, 오프라인 전멸의 DB 실패 기록은 이 조각에서 바꾸지 않았습니다. 실서버 피해·사망은 단계 끝 통합에서 확인합니다. 전체 PARTIAL.

## MDF-P1 M08 통합 (2026-10-15)
- god 지시에 따라 Creed+Jim PASS인 grok/mdf-p1-m08(ad1dc2c)을 Oscar 브랜치에 로컬 merge했습니다. PROGRESS/TEST_RESULT 말미 충돌은 양쪽 기록을 모두 보존했습니다. Networking 자동 합치기 diff는 RunRuntime에 COMBAT::hurtLinger를 연결하는 생성자 변경이며 O28 요청 경로는 유지합니다.
- M11 추가 요구: 잔류 개체 사망으로 전멸할 때 DB 런이 PAUSED로 남지 않고 FAILED가 되도록 처리·검증해야 합니다. 현재 합치기에 완료로 포함하지 않습니다.

## MDF-P1 S09i1 (2026-10-15)
- 재련 UUID 저널·long adapter 연결. 기존 replay-before-owner 순서를 account→journal→equipment로 바꿔 이전 세대/비활성 캐릭터 재생을 차단했습니다. 원래 grade/failures/success와 최신 grade/failures를 분리합니다. 비용·추첨·이력·신규 재화원장(eventId=저널eventId)·저널은 같은 트랜잭션입니다.
- 재련은 현재 실행 호출자가 없어 새 UI/패킷을 만들지 않았습니다. 기존 API와 새 journaled API를 유지합니다. 계승/run·quest 등 O28/M11/M12/M13b/M14 남음. 전체 PARTIAL.

## MDF-P1 M13a 통합 (2026-10-15)
- god 지시에 따라 grok/mdf-p1-m13a(38c343c, Creed+Jim PASS 통보)를 로컬 no-ff merge했습니다. 추가 파일은 scripts/m13-fixture-recovery.ps1 및 docs/M13_RECOVERY_EVIDENCE.md 2개이며 코드·기존 기록 충돌은 없습니다.
- god 통보상 Jim 재현 RTO 2.656초/복원 drill DB 정리 확인. Oscar는 요청된 compileJava만 확인했으며 복원훈련을 다시 실행하지 않았습니다. M13b 인증 확인·전체 P1은 남습니다.

## MDF-P1 S09i2 (2026-10-15)
- refine_item common UUID router와 client requestRefinement/receipt parser 연결. 원 grade/failures/success receipt는 캐시에 적용하지 않고 별도 RefinementStateRequest/Response로 최신 상태를 조회합니다. 조회도 현재 세대·활성 캐릭터 소유권을 검증하고, client는 장비별 correlation·유효범위·선택 경계로 오래된 상태를 거릅니다.
- 기존 전투 계산은 refinement_grade를 소비하지 않습니다. 이번 조각은 전송/조회 API까지 연결하고 일반 장비 화면의 새 재련 버튼은 추가하지 않았습니다. 재련 효과 연결 및 UI 노출은 별도 범위 확인이 필요합니다. 계승/run·quest 등 O28/M11/M13b/M14 남음. M12/V027 그록 담당·OscarV028부터 유지. 전체 PARTIAL.
