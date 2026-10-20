# 데이터·통신 계약

> 문서 ID: DCD-001 · 분류: 데이터 계약 · 상태: 기준 · 적용 기준: 2026-09-23

### MDF-P1 개발판 부활 상태 계약 (R8/J09, 2026-10-14)

개발판 protocol29는 서버→클라이언트 `run_revive_state`에 사망 여부, 남은 부활 횟수, 클릭 대기 tick, 자동 부활 대기 tick, 부활 불가/관전 여부를 보낸다. 서버가 사망 직후·매20tick·실제 부활 뒤 갱신하며, 실제 클릭100tick/자동300tick 판정과 차감은 서버가 소유한다. 클라이언트는 접속 변경·생존 시 표시 상태를 지운다. 필드 사망에는 던전 안내를 적용하지 않는다. 라이브 protocol28 배포는 이 변경에 포함하지 않는다. 배포 JAR의 Mixin 적용과 연결 경합은 통합 실행 검증 대상이다.

### MDF-P1 요청 저널 1단계 (O28, 2026-10-14)

V025의 `operation_requests`는 UUID 봉투의 서버 계산 SHA-256 본문 해시, 소유 계정·세대·활성 캐릭터, 원결과, 이벤트 UUID, 반영 상태를 저장한다. 키 정렬·숫자 표기 정규화 후 해시하며 배열 순서는 보존한다. 강화의 원장 예약·차감·원결과 확정은 같은 트랜잭션이다. 확정 뒤 상태는 `pending_recovery`이고, 현재 세대·활성 캐릭터를 검증한 실제 반영 확인만 `completed`로 바꾼다.

확정 결과의 동일성도 정밀 숫자를 보존한 정규 JSON 문자열로 비교한다. Gson의 수치 `equals`를 사용하지 않아 서로 다른 큰 정수가 같은 결과로 승인되지 않는다.

저장 계층은 강화의 기존 long 요청 호환 어댑터도 유지한다. 기존 long ID는 계정·세대·작업 종류와 함께 UUID로 연결하며, 직접 UUID 재전송의 상관 ID가 바뀌어도 원래 도메인 요청에 연결한다. 기존 기능 테이블·행은 변경하지 않는다. 다른 변경 요청과 실제 월드 반영 확인·재접속 복구는 후속 조각이다. 새 코드의 강화에는 V025가 필요하다. 이전 코드로 되돌리면 기존 테이블을 계속 읽을 수 있으며, 새 저널을 자동 삭제하지 않는다. 이전 배포 JAR로의 롤백 실행은 아직 검증하지 않았다.

S09c의 개발판 protocol30은 서버 발급 `request_context`와 UUID `operation_request`/`operation_receipt`를 사용한다. 첫 적용은 강화다. 작업 이름64자·본문8192자 한도로 디코딩하고 서버가 작업·본문·세대·활성 캐릭터를 검증한다. 영수증은 원결과, 이벤트 UUID, 상태와 재생 여부를 구분한다. 클라이언트는 요청 ID·상관 ID·현재 문맥을 확인하고 100tick 뒤 같은 불변 봉투를 재전송한다. 확정 불가능한 DB 통신 오류는 `accepted` 상태로 유지해 동일 UUID로 복구하며, 확실한 검증 거부만 `rejected`다. 인벤토리는 항상 최신 조회로 갱신한다. 라이브 protocol28 배포와 실제 월드 반영 ACK는 포함하지 않는다.

개발판 protocol은 35이다. 34 이하 클라이언트는 Hello에서 "달마루 통신 버전이 맞지 않습니다."로 끊기고 Welcome을 받지 못한다. 파티 콘텐츠 `party.json`의 protocol 34와 최대 4명은 그대로다. 금고·상점·채집·제작·직접 거래는 기존 `operation_request`로 보내고, 좌표는 서버 플레이어 위치만 쓴다. 재련·계승은 이 층에 없다. 역참 요청/응답을 포함한다. protocol30 이후 추가된 `refinement_state_request`/`refinement_state_response`(S09i2) 등 신규 패킷을 포함하며, 서버·클라이언트가 `DalmaruNetworking.PROTOCOL_VERSION` 한 상수를 공유한다. Hello 버전이 다르면 서버가 접속을 끊고("달마루 통신 버전이 맞지 않습니다.") Welcome을 보내지 않으며, 클라이언트는 같은 버전의 Welcome만 호환으로 인정한다. `verifyProtocolVersion`이 상수, 핸드셰이크 코덱, 불일치 거부 순서를 검사한다. 라이브 protocol28과 운영 설정은 바꾸지 않았다.

이 문서는 달마루의 콘텐츠 정의, 영구 저장, 서버 요청·응답이 지켜야 할 최소 계약을 정한다. 이는 Java 클래스나 DB 마이그레이션 파일이 아니라 구현이 따라야 하는 형태와 불변 조건이다. 수치 자체는 [시험 콘텐츠 명세](PILOT_CONTENT_SPEC.md), 게임 규칙은 [세부 규칙과 예외 처리](RULES_AND_OPERATIONS.md)를 따른다.

## 1. 공통 식별자와 버전

- 화면 이름은 바꿀 수 있지만 `id`는 배포 뒤 바꾸지 않는다.
- 콘텐츠 ID는 소문자 영문, 숫자, 밑줄, 점만 쓰며 영역 접두사를 둔다. 예: `skill.warrior.sweep`, `run.bongsudae`, `item.ironstone`.
- 캐릭터·장비·공략·경매·원장·요청은 UUID를 쓴다. 인증 계정 UUID와 캐릭터 UUID를 섞지 않는다.
- 모든 콘텐츠 묶음에는 불변 `contentVersion`이 있다. 공략은 시작 당시 버전으로 고정되며 진행 중 교체하지 않는다.
- 시간은 저장 시 UTC ISO-8601, 주간 초기화 계산은 `Asia/Seoul`을 사용한다. 기간은 시작 포함·끝 제외다.

## 2. 콘텐츠 정의의 공통 형식

모든 JSON 정의에는 최소한 `id`, `schemaVersion`, `contentVersion`, `display`, `enabled`가 있어야 한다. 배포 전 검사기는 중복 ID, 존재하지 않는 참조, 순환 선행 조건, 음수 비용, 확률 범위, 무기 호환, 인원별 기믹 수행 가능성을 거부한다.

```json
{
  "id": "skill.warrior.sweep",
  "schemaVersion": 1,
  "contentVersion": "pilot-1",
  "display": { "name": "횡참", "description": "전방을 넓게 벤다." },
  "enabled": true
}
```

### 스킬 정의

```json
{
  "id": "skill.warrior.sweep",
  "class": "warrior",
  "branch": null,
  "weaponProfiles": ["two_hand_blade", "sword_shield"],
  "unlock": { "level": 10, "skillPoints": 1, "maxRank": 5 },
  "cast": { "energy": 20, "cooldownMs": 4500, "range": 3.0, "targeting": "front_arc" },
  "effects": [
    { "kind": "damage", "formula": "physical_attack * rank_multiplier", "rankMultiplier": [1.10, 1.20, 1.30, 1.40, 1.50] }
  ],
  "combatRules": { "canCrit": true, "refreshesCombat": "on_valid_hostile_hit", "removesReviveImmunity": true },
  "serverChecks": ["active_character", "weapon_profile", "skill_rank", "energy", "cooldown", "range", "line_of_sight"]
}
```

`effects`에는 피해·회복·보호막·제어·이동·자원 변화가 명시된다. 지속 효과에는 기간, 중첩 한도, 갱신 방식, 사망 뒤 유지 여부를 적는다. 고유 자원은 무기·분기 정의에 최대치, 초기치, 획득·소비·감소 규칙을 따로 둔다. 클라이언트는 정의를 표시할 수 있지만 비용·거리·피해·성공 여부는 서버가 다시 계산한다.

### 장비·강화·재련 정의

```json
{
  "id": "item.training_two_hand_blade_t10",
  "slot": "main_weapon",
  "weaponProfile": "two_hand_blade",
  "requiredLevel": 10,
  "allowedClasses": ["warrior"],
  "grade": "ordinary",
  "baseStats": { "physicalAttack": 41 },
  "binding": "character_bound",
  "enhancement": { "maxLevel": 15, "table": "enhancement.standard" },
  "refinementPath": null
}
```

강화표에는 목표 단계, 기본 성공률, 실패당 보정, 비용·재료가 있으며 실패 보정은 장비 인스턴스에 저장한다. 재련표에는 목표 등급, 실패당 10%p 보정, 시도 비용, 성공 시 강화 유지 비용 비율이 있다. `item_instance`에는 원본 정의 ID와 별개로 현재 강화, 재련 보정, 귀속, 위치, 계승 잔액을 기록한다.

### 공략·보상 정의

```json
{
  "id": "run.bongsudae",
  "contentVersion": "pilot-1",
  "party": { "min": 1, "max": 4, "tiers": [1, 2, 4] },
  "difficulties": [
    { "id": "first_step", "requiredLevel": 15, "weeklyClearLimit": 3, "timeLimitMinutes": 45 },
    { "id": "training", "requiredLevel": 20, "weeklyClearLimit": 3, "timeLimitMinutes": 45 }
  ],
  "rewards": { "personalTable": "reward.bongsudae", "specialTable": "special.bongsudae" }
}
```

특별 보상 정의에는 최소 입찰가, 귀속, 유찰 시 처리, 수량을 넣는다. 인원 보정, 보스 단계, 기믹 요구 인원은 콘텐츠 데이터가 갖고 서버가 시작 때 고정한다.

### 퀘스트·제작 정의

퀘스트는 선행 조건, 목표, 완료 보상, 재수령 규칙, 실패하지 않는 필수 동선 여부를 가진다. 제작식은 제작법 ID, 숙련도·설비 조건, 입력 재료, 결과 정의, 귀속 전파, 확정 성공 여부를 가진다. 주문 제작은 제작식이 아니라 영구 저장된 주문 인스턴스로 관리한다.

## 3. 영구 저장의 기준 원본

| 저장 영역 | 기준 원본 | 핵심 키·불변 조건 |
|---|---|---|
| 계정·캐릭터 | PostgreSQL | 계정 UUID와 캐릭터 UUID 분리, 계정당 활성 캐릭터 하나 |
| 장비 인스턴스 | PostgreSQL | 장비 UUID 하나당 소유자와 위치 하나 |
| 재료 묶음 | PostgreSQL | 수량은 0 이상, 분할·병합 뒤 총량 보존 |
| 재화·원장 | PostgreSQL | 잔액 음수 금지, 모든 증감에 사유·요청 UUID |
| 공략·참가자 | PostgreSQL + 서버 실행 상태 | 고정 명단·난이도·콘텐츠 버전·주기 ID·부활 기록 |
| 예약·보상·경매 | PostgreSQL | 완료·정산은 한 번만, 미수령은 보관함 |
| 퀘스트·생활·주문 | PostgreSQL | 캐릭터별 진행, 계정 공유 공간은 별도 소유자 |

바닐라 가방, 플레이어 NBT, 월드 드롭은 RPG 물품의 기준 원본이 아니다. 게임 화면과 월드 반영이 중단돼도 DB의 소유권과 보관함 이벤트로 다시 맞춘다.

## 4. 요청 멱등성과 연결 세대

경제·소유권·성장 요청은 다음 공통 봉투를 사용한다.

```json
{
  "requestId": "UUID",
  "connectionGeneration": 17,
  "activeCharacterId": "UUID",
  "operation": "enhance_item",
  "payload": { "itemInstanceId": "UUID", "targetLevel": 3 }
}
```

- `requestId`는 같은 요청의 재전송에 재사용한다. 서버는 요청 본문 해시를 함께 저장한다.
- 같은 ID와 같은 본문은 이전 확정 결과를 그대로 반환한다. 같은 ID에 다른 본문이면 거부한다.
- `connectionGeneration`은 재접속 때 증가한다. 더 낮은 세대의 쓰기 요청은 거부한다.
- 서버는 인증된 계정과 활성 캐릭터를 연결한다. 클라이언트가 임의의 캐릭터 ID를 보내도 권한이 생기지 않는다.
- 결과는 `accepted`, `rejected`, `completed`, `pending_recovery` 중 하나로 응답하고, 확정된 재화·소유권 변화에는 원장 ID 또는 보관함 이벤트 ID를 준다.

## 5. 대표적인 원자 작업

| 작업 | 한 번에 확정할 항목 |
|---|---|
| 강화 | 장비 잠금, 비용·재료 차감, 난수 결과, 실패 보정, 원장, 응답 이벤트 |
| 재련 | 장비 잠금, 시도 비용, 성공 시 유지 비용, 등급 변환, 재련 보정, 원장 |
| 계승 | 원본 소모, 대상 잔액, 귀속, 크레딧 산정, 원장 |
| 직접 거래 | 양쪽 확인, 양쪽 물품 위치, 재화, 취소·시간 만료 잠금 해제 |
| 클리어 | 예약 완료, 개인 보상 소유권, 특별 경매 생성, 공략 종료 기록 |
| 경매 정산 | 최고 입찰 확정, 예약 잔액 해제·차감, 낙찰품, 분배금, 유찰 처리 |
| 제작·주문 | 재료 잠금·차감, 조건 검사, 결과물, 수수료, 보관함 이벤트 |

게임 서버는 DB 확정 뒤에만 성공을 화면에 표시한다. DB 확정 뒤 월드 반영이 중단되면 `outbox` 또는 보관함 이벤트를 재접속 때 한 번 반영한다. 확정된 강화·경매·보상은 재추첨하거나 중복 지급하지 않는다.

## 6. 공략 상태 계약

`READY → ACTIVE → SETTLEMENT → CLOSED`가 기본 흐름이다. 전원 접속 종료 시에만 `PAUSED`로 들어갈 수 있고, 최대 5분 또는 공략 시간 상한까지 유지한다.

| 상태 | 허용 행동 | 고정 또는 기록할 값 |
|---|---|---|
| READY | 준비 확인·스킬 장착·입장 취소 | 원래 참가자, 선택 난이도 |
| ACTIVE | 전투·기믹·사망·재접속 | 장착 목록·인원 구간·콘텐츠 버전·주기 예약 |
| PAUSED | 원래 참가자 재접속 | 정지 시작 시각, 재개·만료 시각 |
| SETTLEMENT | 개인 보상·경매·보관함 지급 | 자격자·보상 UUID·경매 상태 |
| CLOSED | 조회만 가능 | 완료/실패 사유, 종료 시각 |

참가자 기록에는 `joinedAtStart`, `validActionAtLeastOnce`, `leftVoluntarily`, `kicked`, `deathState`, `revivesUsed`, `disconnectState`, `rewardEligible`를 둔다. 보상 자격은 클라이언트의 피해량 표시에 의존하지 않는다.

## 7. 클라이언트와 서버의 경계

| 영역 | 클라이언트가 보낼 수 있는 것 | 서버가 확정할 것 |
|---|---|---|
| 전투 | 스킬 사용 의도, 조준 방향, 입력 시각 | 대상·거리·시야·쿨다운·기력·피해·제어 |
| 이동·회피 | 입력 의도 | 유효 위치·공략 경계·면역·충돌 |
| 성장 | 능력치 투자·초기화 요청 | 남은 점수·비용·캐릭터 조건 |
| 공략 | 준비·입장·포기·투표 | 명단·난이도·주기 예약·전멸·보상 |
| 경제 | 입찰·거래·강화·제작 요청 | 잔액·소유권·확률·원장·정산 |

클라이언트는 예상 피해나 남은 시간, UI 애니메이션을 표시할 수 있지만 확정값으로 저장하지 않는다. 서버 응답에는 화면을 갱신할 최소 상태와 버전을 포함하고, 클라이언트는 순번이 뒤처진 상태 갱신을 버린다.

## 8. 구현 전 정적·통합 시험

- 콘텐츠 로드: ID 중복, 누락 참조, 확률 0~100 밖, 음수 비용, 무기·직업 불일치, 불가능한 인원 기믹 거부.
- 멱등성: 동일 요청 재전송은 동일 결과, ID 재사용 본문 변경은 거부.
- 소유권: 한 장비의 이중 위치와 재료 음수·총량 변화를 거부.
- 공략: 초기화 직전 시작, 실패 후 예약 해제, 재접속·전멸·정산 후 중복 보상 없음.
- 경매: 동시 입찰, 연장 상한, 단독 자격자, 접속 종료 낙찰, 장애 뒤 재정산.

구현 시 각 시험은 [요구사항 정의서](REQUIREMENTS.md)의 ID와 연결해 `TEST_RESULT.md`에 결과 또는 미실행 사유를 남긴다.

## 9. P2 전직 완료 증명 (S05)

전직은 레벨 10과 아래 세 증명을 모두 요구한다. `character_quest_progress`의 해당 캐릭터 행에서 `content_version='pilot-2'`, `state`가 `COMPLETED` 또는 `REWARDED`여야 한다. 다른 버전·캐릭터·직업의 증명이나 `ACTIVE`/`AVAILABLE` 행은 사용할 수 없다. 이전 `pilot1.zombie_hunt` 완료만으로는 전직할 수 없다.

| 증명 | quest_id |
|---|---|
| 선택 직업 마을 방문 | `profession.<warrior/archer/mage/rogue>.village_visit` |
| 선택 직업 시험 | `profession.<warrior/archer/mage/rogue>.trial` |
| 네 갈래의 길 이야기 완료 | `story.four_paths` |

키와 버전은 `CharacterRepository.professionGateFlags()` 및 `PROFESSION_GATE_VERSION`으로 공유한다. S06 이야기·S10 마을 연동 전에는 시험 데이터에서만 완료 증명을 만든다. 클라이언트 요청에 완료 플래그를 추가하거나 전직 요청 자체로 증명을 생성하지 않는다. 이 조건 변경은 기존 보상 지급과 분리하며, 이미 확정한 동일 UUID 요청은 원래 결과를 재생한다.

## 10. 역참 귀환 (P2-S08)
- `waypoint_request(requestId, characterId, action, waypointId)`: action은 list/return/cancel. 캐릭터는 선택된 서버 세션과 일치해야 한다. 이름 길이는 128자, action 16자 제한. 최초 해금은 서버 위치만 판정하며 클라이언트 완료 주장은 없다.
- `waypoint_response(requestId, characterId, status, waypoints)`: 목록과 처리 안내. 클라이언트는 현재 화면 요청·캐릭터와 일치하는 응답만 표시한다. 최종 이동 결과는 서버 채팅으로 알린다. 개발 명령 `/dalmaru-waypoint [return <id>|cancel]`도 동일 서버 처리 경로를 사용한다.
- 160tick 후에만 `waypoint_return` UUID 요청과 900초 쿨다운을 같은 트랜잭션으로 확정한다. 준비 중 취소에는 쿨다운이 없다. 완료 후 ACK, 실패 시 기존 쿨다운 복구. 취소·성공 처리 완료 요청은 다시 이동하지 않는다. 저장 결과가 불명확하면 접속을 종료하고 재접속 시 pending_recovery를 처리한다.
- V031 테이블은 캐릭터 FK cascade. 마을 설정 제거 시 해금 기록을 보존한다. 되돌릴 때 기능을 비활성화하고 테이블/요청 기록을 보존하며 자동 DROP하지 않는다. 완료 DB 호출은 서버 스레드에서 짧은 연결/statement/lock timeout을 사용한다.

마을 경계 통합: 안전 전환·방문·역참 대상은 `VillageCatalog`의 설정만 읽는다. `waypointId`는 `village.id()`이며 좌표가 바뀌어도 해금 ID는 유지된다. 이전 `town_safe_zones`는 롤백용으로 보존하며 `/dalmaru-town` 등록 명령은 사용하지 않는다.

## 11. 1~20 이야기 (P2-S06)
- content/story.json의 pilot-2 구간 순서와 NPC/필수전투/증거/보상소유권을 사용한다. character_quest_progress에 현재 구간 ACTIVE, 보고 후 REWARDED와 개인 NPC 증거를 저장한다. S05 증명키·버전과 일치하며 client 완료 주장은 저장하지 않는다.
- 기존 OperationRequest의 story_interact/story_report는 body {chapterId,npcId}만 허용한다. 서버 세션·등록NPC·서버위치를 확인하고 같은 UUID는 저장 결과로 재생한다. DB만 바꾸는 요청이므로 저널 완료 ACK까지 같은 트랜잭션에서 끝난다.
- story_state_request(requestId,characterId) / story_state_response(requestId,characterId,state JSON). 현재 구간·전투 진척·등록NPC·부족조건을 전달한다. 클라이언트는 현재 캐릭터/기대 요청/수신 순서를 확인하고 다른 캐릭터·오래된 상태·형식 오류를 적용하지 않는다.
- story_combat UUID는 캐릭터+처치entity UUID로 결정한다. 처치XP는 CharacterRepository.grantExperience와 같은 트랜잭션으로 진행에 반영한다. 클라이언트는 처치 XP·좌표를 보낼 수 없다. 시험은 양쪽 모두 같은 직업마을 안일 때만 해당 직업 .trial에 쌓인다.
- 조사 보상/600XP는 S07 하나가 소유한다. S06은 InvestigationEvidence.completed(Connection,UUID)로 완료만 확인하며 보고 시 지급0이다. 실제 어댑터는 S07 reportConfirmed를 호출한다. 최초 공략500XP도 기존 공략 지급만 사용한다.

S06/S07 실연결: Networking이 `QuestStageRepository::reportConfirmed`를 주입한다. 같은 Connection/자동커밋금지/FOR SHARE 계약을 유지한다. S07 report 보상600XP·조사물품만 한 번 지급하고 S06은 이야기REWARDED 전환만 수행한다. S07 신뢰경계는 f71ccb0 통합 뒤 현장·증거·보고 위조 요청 3회가 실제 완료 reader와 S06 보고를 해금하지 못하는 검사로 확인했다. 정상 완료·재전송의 1회 지급도 유지하며 최종 독립 검토/승인은 별도다.

## P3 V039 금전 우편 원장 전환 (2026-10-18 god 승인)
- V038 우편 이벤트 행을 S13이 각각 수령한다. 원장 `mail_event_id`는 기존 `character_mailbox.event_id`를 가리키는 역사적 UUID이며, 우편 보관 이력 삭제 후에도 값은 유지한다.
- `mail.claim`은 `(character_id, mail_event_id)`로 한 번만 기록하고, 나머지 reason은 기존 `(source_run_id, character_id, reason)` 중복 보호를 유지한다. 수령의 행 잠금·claimed_at·지갑 상한 판정은 유지한다.
- 기존 수령 원장은 캐릭터·런·currency.copper·수량·claimed_at으로 유일하게 확인되는 우편 UUID만 이전한다. 모호하거나 중복된 이력은 V039 전체를 롤백하며 임의 연결하지 않는다.
- V039 적용 후 이벤트 UUID를 전달하는 수령 코드를 사용한다. 기존 수령 코드는 필수 이벤트 제약으로 확정을 거절하므로 구 수령기와 혼합 운영하지 않는다. V039 실패는 구 스키마·데이터를 유지한다. 여러 금전 우편 수령이 기록된 뒤에는 기존 3열 UNIQUE 복원이 불가능할 수 있으므로 검증 없이 V038로 축소하지 않는다.
