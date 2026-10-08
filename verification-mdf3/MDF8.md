# MDF-8: 1인 RPG 체력·장비·피해 연결

상태: 구현 완료, Jim QA·Creed 리뷰 대기. 브랜치: oscar/mdf8-solo-clear-20261008.
승인 A안에 따라 설계 체력 10,800/14,400과 목표 시간을 유지했습니다. 자동 장비 지급·임의 공격력 배율은 없습니다.

## 변경과 경로
- CombatUnits: 20 RPG 단위 = 엔진 1단위. 1인 초행·수련 최대 체력만 지원하며 엔진 상한을 넘는 값은 거부합니다.
- EquipmentRepository.equipped / PilotEquipment: 실제 EQUIPPED 기록의 소유권·슬롯·요구 레벨·직업을 검증하고 명시적 10/15/20레벨 무기·방어구·장신구 수치를 적용합니다. 능력치 예산 30/45/25%, 방어구 부위 20/40/20/20%, 강화 +1~5 단계당 2%p·이후 3%p. starter.slot.* 등 미정의 장비는 수치 0입니다.
- CombatRuntime: 장비 공격력·방어력·능력치와 체력 단위를 적용합니다. 프로필/장비 갱신 시 현재 체력 비율과 기본 공격 대기 시간을 유지합니다. 무기별 기본 공격 간격을 서버가 제한합니다.
- DalmaruNetworking: 기본 공격·발사체, 기존 피해 기술 10개, 플레이어 피격을 공통 계산기에 연결합니다. DB 장비 조회는 비동기이며 연결·세션·활성 캐릭터·revision으로 오래된 응답을 차단합니다. 강화 후 재조회합니다. 장비 목록 요청은 자동 생성하지 않습니다.
- RunRuntime: 보스 HP를 540/720, 수련 최종 993.6으로 변환합니다. 보스 방어 80/55 및 수련 +20, 보스 피해와 수련 ×1.15를 연결합니다. 방패 밀치기 130·최종 화염 설계값 180을 반영하고 좀비 자체 근접 공격력은 0으로 설정합니다.
- RpgDamageMixin / 리소스 설정: hurtServer 진입에서 엔진 피해를 변환합니다. 이미 계산한 RPG 피해는 ThreadLocal과 try/finally로 중복 변환을 막고 방어 이중 적용을 차단합니다. 기존 반환·넉백·처치 이벤트 경로를 유지합니다.
- CombatCalculator: 분수 보스 피해를 허용하고 유한 입력을 검증합니다. 큰 피해 및 중간 곱셈 overflow는 Integer.MAX_VALUE로 포화시켜 0 피해가 되는 오류를 막습니다.

## 15레벨 권장 전사 재현
직접 능력치 40/20/10 + 장비 8/6/4, 최종 주력 48·체력 26입니다. 실제 DB 장착 기록에 명시적 장비 ID가 있어야 이 수치가 적용됩니다.
- HP 740, 무기 공격 51, 물리 방어 75, 술법 방어 38.
- 보스 물리 방어 80에 비치명 기본 피해 round(51 × 1.192 × 500/580) = 52.
- 철문수장 208타, 원혼봉수장 277타. 초당 유효 1타 가정 합계 485초이며 실제 처치 시간은 아닙니다.

## 실행 검증
- PASS: compileJava compileClientJava testClasses verifySoloCombatDefinitions verifyCombatCalculator --no-daemon, exit 0.
- PASS: 장비 소유권·중복 슬롯·레벨·직업·강화 중복 방지, 마법 채널·쌍도끼 55%, HP/피해 왕복·분수 피해·큰 피해 포화 assertions.
- PASS: Fabric 서버 Done, Mixin 로딩 오류 없음. 서버측 엔진 경계 시험에서 max_health 540/720/993.6에 피해 2.6 적용 후 Health 537.4/717.4/991.0 확인. 이는 실제 RunRuntime 보스 및 플레이어 완주 검증이 아닙니다.
- 초기 콘솔 시험은 청크 미로드로 No entity was found가 나왔습니다. 청크 로드 후 시험을 통과했고 고유 태그 시험 엔티티와 임시 강제 로드를 정리했습니다.
- 서버 exit 0. 자체 wrapper·서버·Gradle daemon 종료 후 Java 프로세스 0 확인. server.properties 설정값 해시 기동 전후 동일, 수동 설정 변경 없음.
- 증거: MDF8_SERVER_CHECK.json 및 임시 서버 로그 C:/Users/gusdl/AppData/Local/Temp/oscar-mdf8-smoke-sHhh7I/logs/server.console.log.

## 한계·QA 인계
- 실제 수동 플레이·1인 공략 완주는 미검증입니다. Jim이 명시적 장비 DB 장착 상태로 기본 공격/기술/보스 피격, 피해 중복 변환·방어 중복 적용, 체력 비율·부활 35%·처치·보상 1회성을 검증해야 합니다.
- 바닐라 근접 치명타 조건을 보존했습니다. 설계 5% 확률 RNG는 이번에 구현하지 않았습니다. 발사체는 장비 기본 100% 배율이며 충전·탄속·랜덤 치명 보정은 별도 검증이 필요합니다.
- 장비 획득·장착 UI나 아이템 지급은 만들지 않았습니다. 자연 회복·보호막·다인 전투의 전체 정합성은 미검증입니다. 다인 일반 해법을 확정하지 않습니다.
- Creed는 Mixin 훅·단위 경계·서버 소유권·비동기 응답 검증을 리뷰해야 합니다.
- main 직접 커밋/머지·push 없음. 기존 불명확 미추적 파일 4개를 보존했습니다. TEST_RESULT.md는 과거 민감 기록 때문에 커밋에서 제외하고 이번 결과를 작업본에만 추가합니다.

## F1 후속 수정 (2026-10-09)

장비 DB 조회가 부활을 가로지를 때 이전 플레이어 entity를 갱신하던 결함을 수정했습니다. 성공/오류 응답 모두 서버 스레드에서 UUID의 현재 접속 플레이어를 재조회합니다. 연결·세션·활성 캐릭터·revision 검증과 CombatRuntime의 체력 비율 유지 동작을 보존합니다.

검증: compileJava compileClientJava testClasses verifyEquipmentRespawn --no-daemon --console=plain, exit 0. EquipmentRespawnCheck는 제품 refreshCombat 메서드를 그대로 추출해 지연된 저장소 응답과 교체 가능한 플레이어 목록 대역으로 실행합니다. 조회 시작 → 동일 UUID 새 entity로 교체·35% HP → 응답 처리 순서에서 이전 entity가 갱신되지 않고 새 entity에만 적용되는지 확인합니다. 기존 HEAD의 콜백은 기대 assertion 실패로 F1을 재현했고 수정 콜백은 PASS했습니다. HP 680/238 → 740/259는 전투 대역의 비율 검사이며 실제 엔진 관측값은 아닙니다. 실제 서버 부활·DB 연결·완주는 미검증입니다.

TEST_RESULT.md·서버 설정은 변경하지 않았습니다. 승인된 미추적 e HEAD만 삭제했습니다. 비밀번호와 동일한 문자열이 원격 문서 이력에 존재하여 god 지시로 이력 재작성·bundle·reflog/gc는 보류했습니다.

## 브랜치 push 및 실서버 검증 요청 (2026-10-09)

사람 승인에 따라 oscar/mdf8-solo-clear-20261008을 origin에 push했습니다. 원격 브랜치 HEAD 99849859b270bb6984d5379275ce02395353a871을 ls-remote로 확인했습니다. main 병합·force push·비밀번호 회전·이력 재작성은 수행하지 않았습니다.

실서버 autoplay 검증: BLOCKED. DALMARU_DB_PASSWORD 환경변수가 없어 서버/봇을 시작하지 않았으며 god에 환경변수 제공을 요청했습니다. 사망→부활 후 장비/HP 35%, 실제 보스 HP, 보상 1회 지급은 모두 미실행입니다. 기존 대역 회귀 PASS와 서버측 엔진 경계 PASS는 이 항목들의 실제 플레이 PASS를 뜻하지 않습니다. 환경변수 제공 후 봇 가능 범위를 확인하고 우회한 단계는 서버측 검증, 봇으로 수행할 수 없는 단계는 수동 필요로 분리하여 기록해야 합니다.

## 실서버 autoplay 후속 (2026-10-09)

환경변수 제공 후 검증을 재개했습니다. 장착 HP37·자연 부활35% 기대값, DB 지연 중 부활 후 현재 entity 최대 HP34→37, 실제 보스HP540/720, 완료/보상1회 및 요청 재전송 후 추가 지급0을 확인했습니다. 명령 처치·공간 격리는 서버측 검증이며 실제 1인 완주 PASS가 아닙니다. 발판 지형 간섭으로 질식하는 결함을 확인했고, 정밀 회복 비율·완주는 수동 필요입니다. 상세: MDF8_AUTOPLAY.md 및 MDF8_AUTOPLAY_RESULT.json. 이전 환경변수 미제공 BLOCKED 기록은 재개 전 상태입니다.

## 지형 질식 후속 수정 (2026-10-09)

BeaconTowerMap.build에서 내부 지형을 비운 뒤 구조물을 생성하도록 수정했습니다. 제품 생성 코드의 고체 지형 회귀 시험과 컴파일 PASS. 기존 봇 creative 확인→survival 전환 후 새 spawn에서 수동 fill/setblock 없이 74.1초 생존·질식/사망0·발/머리 AIR를 확인했습니다(보스 NoAI 분리, 서버측 검증). 정상 전투 완주·정밀 회복은 수동 필요입니다. 상세와 캐시3개 경로: MDF8_AUTOPLAY.md, 증거: MDF8_AUTOPLAY_TERRAIN_RESULT.json.
