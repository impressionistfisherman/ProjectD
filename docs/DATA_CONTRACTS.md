# 데이터·통신 계약

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
