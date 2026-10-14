# MDF16/J02 후속 QA — Oscar, 2026-10-13

상태: 구현 수정 및 아래 자동/실제 클라이언트 검증 완료. 운영 미적용. Jim의 독립 QA 결과가 아니라 Oscar 직접 검증입니다.

## 수정
- 기술/입장 화면 폭을 현재 해상도에 맞추고 기술 줄 간격·footer 간격을 계산합니다. HUD 기술 이름은 각 슬롯 폭으로 제한합니다. 미사용 문자 스킬키16개 등록을 제거했습니다(숫자 슬롯·Grave·Alt 유지).
- 캐릭터 전투 프로필 미설치 상태의 준비/입장을 차단합니다.
- 실제 발견한 결함: 필드 사망 후 vanilla 조기 respawn이 3초 대기 중 기본spawn 마을을 새 안전점으로 저장해 사망전 안전점을 덮었습니다. FieldRevive에 사망당시 안전점/게임모드/기한을 고정했습니다. 조기respawn은 대기 중 spectator로 행동을 차단하고, 완료 시 위치·모드·HP/기력을 복구합니다. 대기 중 안전점 활성화를 건너뛰며 disconnect/서버종료 때 임시 spectator를 해제합니다.

## 검사와 증거
- verifyClientScreenBounds PASS: 실제제품 init 메서드의320×240/427×240/640×360/960×540, 버튼잘림·겹침. 대역은8개기술(최대행 스트레스), 실제전사6개입니다.
- 실제 Minecraft 클라이언트 PASS(client-input.log): 실제 창640×480, GUI320×240에서 기술/입장 버튼bounds·겹침과 실제렌더 확인. 주입된 Minecraft.handleKeybinds 경로를 호출하여 생활1→핫바1, 전투2→vanilla슬롯불변, 전투6→핫바6 확인. Grave/Alt 기본값, 문자기술키등록제거 확인. 사진skill-320x240.png/run-320x240.png/combat-hud.png. vanilla 채팅안내/튜토리얼toast가 일부사진에 표시되며 제품버튼 bounds와 구분합니다.
- 위 클라이언트 검사는 선택/프로필/장착/전투 상태를 시험snapshot으로 설치하고 KeyMapping.click 및 실제key handler를 호출했습니다. 물리키보드/마우스 조작이나 서버로 실제스킬이전송되는 것까지 이 시험으로 주장하지 않습니다.
- 별도 4인 실제bot/packet PASS(packet-result.json): protocol28,4인READY,오래된준비UUID차단,장착변경후전원ready초기화,4인ACTIVE방송,DBfrozen snapshot,ACTIVE편집거부,숫자slot1기술사용 및 같은request중복비용차단.
- 실제 필드 부활 PASS(field-fixed.log/field-result.json): 60서버tick(외부관측2900ms) 뒤 사망전checkpoint(15.5,101,0.5), HP12/24(50%), 다음HUD 기력55/100(50%reset 후회복), 전투모드false. 조기respawn이 다른마을에 나타나도point가 유지됨. 자연몹 간섭을 배제한 마지막fixture는 peaceful이며 운영과무관합니다.
- verifyFieldReviveBoundary PASS: 실제fieldDeath/fieldRespawn/clearFieldRevive 메서드로 death point snapshot/조기respawn의기한불변/spectator대기/모드초기화/disconnect복원/미선택vanilla보존.
- 후속 verifyReadyBoundary/verifyClientScreenBounds/verifyReviewRegression/verifySessionLifecycle/compileJava/compileClientJava/assemble PASS(10초). git diff --check PASS. 성공한 전체회귀를 다시 반복하지 않았습니다.

## 실패 이력
- packet첫회: 시험바닥미로드→수정. 다음시험은재접속후선택단계누락으로stamina미설치→선택포함후PASS. 이때 서버ready에도activeProfile미설치차단추가.
- client초기실패: 접속화면순서/실제전사기술6개인데대역8개로cardinality기대/창focus영향. 준비state/실제definition카운트/실제keyhandler호출로교정후PASS.
- field초기실패: SQL::int반올림기대오류, 기존fixture마을갱신/자연몹간섭. 좌표와DB조사로사망전checkpoint덮어쓰기실결함확인. 원인보고후수정된focusedproof가PASS. 실패실행을성공으로취급하지않음.

## 범위와 경계
- 남음: 물리키보드/마우스재지정,4인전투전체완주,실제처치loot/XP/quest보상차단·화로동시조작·던전부활면역의플레이QA. 이전모델/서버연결/헤드리스검사는유효하지만이후QA로모두대체됐다고주장하지않습니다.
- 운영PID4936/JAR SHA256 1B180A3C90FEF48521ADD06EB086AC2DF38E6A5D2BCC541BB74A5D6F298E6539 불변. 운영DB/settings/secrets 미조작. 추가commit/push/merge/배포 없음.
- 시험포트server57702/PG57700(oscar_fixture), 임시폴더Temp/oscar-mdf16-qa. 첨부스크립트의bot이름/character UUID/INIT경로는이번fixture에고정되어있으므로재사용시격리fixture값으로맞춰야합니다. 운영포트로실행하지마세요.
