# P4 S01 서버 계약

기준: 승인된 MDF_P4_PLAN.md / MDF_P4_DECISIONS_GOD.md. S01은 데이터와 Java 계약을 고정합니다. 실제 동작은 S02~S08, 화면 연결은 Grok 담당이며 현재 성공을 반환하는 대체 구현은 없습니다.

## 콘텐츠

- 신규 번들·런 `pilot-p4-1`, 루트 schemaVersion 5. `ContentCatalog.mechanics()`는 frozen snapshot에서 읽은 `BeaconMechanics`입니다. P3 이하(schema 1~4)는 null이며 P4 값을 덧씌우지 않습니다. `RunRepository.frozenContent` 경로를 유지합니다.
- 화로: channelTicks 80, cancelDistance 0.5(이상 취소), interactionRadius 3, overlapTicks 20, counts 1/2/3, simultaneous 1/1/2. 마지막 1초 겹침을 인정하며 잔여 화로 수로 요구 인원을 제한합니다. 성공 화로는 유지하고 전부 성공해야 다음 단계입니다.
- 방치: idleTicks 400 → warningTicks 200 → pulseTicks 60 후 첫 피해(660틱), 이후 60틱 간격. maximumHealthFraction .15를 기존 피해·보호막·무적 경로로 입력합니다. 성공만 경고를 해제합니다.
- 보스: 자세 650/900, 벽 충돌 225, 인원 자세 배율 1/1.7/2.8, 수련 ×1.2, 붕괴 80틱/피해 ×1.3, 종료 후 면역 300틱. HP·피해는 기존 J02 배율/난이도를 한 번 적용합니다. 예고 최소 20틱입니다.
- 모든 값은 임시입니다. 스냅샷과 목록은 불변이며 새 번들이 기존 런을 변경하지 않습니다.

## Grok 연결용 Java 계약

`kr.dalmaru.run.RunMechanicAccess` (구현 바인딩은 S02/S04):

| API | 의미 |
|---|---|
| `startBrazier(ServerPlayer, UUID runId, long revision, BlockPos)` | 서버의 참가자·생존·단계·거리·시야·소유 검증 후 시작. 같은 입력으로 시간을 초기화하지 않음 |
| `cancelBrazier(ServerPlayer, UUID runId, long revision)` | 본인 채널 취소. 클라이언트는 성공/점화를 직접 결정하지 않음 |
| `skillUsed(ServerPlayer)` / `receivedHit(ServerPlayer)` | 실제 기술 사용/HP 또는 보호막에 반영된 피해 통지. 무효·회피·무적 피격 제외 |
| `mechanicSnapshot(ServerPlayer)` | 본인 런의 서버 상태만. 런 없음은 null. 구현이 연결되지 않았으면 화면은 unavailable 처리 |
| `MemberExitAdapter.removeMember(Connection, runId, characterId, expectedRevision, ExitReason)` | S07 구현. 호출자가 계정/요청/런 잠금과 트랜잭션을 소유하며 S12 자격취소·멤버종료·귀환대기를 같은 트랜잭션으로 기록. 커밋 후에만 runtime 반영 |

`Snapshot`: runId, revision, phase, startingPartySize, braziers, neglect, neglectRemainingTicks, bosses. revision은 서버 단계/제어 버전이며 클라이언트가 정하지 않습니다. 단계 변경 직전 값으로 받은 요청은 STALE_REVISION으로 거절합니다. 파티 제어의 원자 잠금·영속 revision은 S06/V040과 연결하며 런 잠금을 잡은 순서대로 판정해야 합니다.

- Phase: GATEKEEPER / BRAZIER / APPROACH / BEACON / COMPLETED.
- Result: STARTED / CANCELLED / ALREADY_CHANNELING / NOT_CHANNELING / UNAVAILABLE / STALE_REVISION / NOT_PARTICIPANT / WRONG_PHASE / DEAD / OUT_OF_RANGE / NO_LINE_OF_SIGHT / BUSY / ALREADY_LIT.
- CancelReason: REQUEST / MOVED / HIT / SKILL / DEATH / DISCONNECT / DIMENSION / PHASE.
- BrazierView: position, lit, channelPlayerId(채널 없으면 null), remainingTicks. 좌표는 불변 복사합니다.
- Neglect: IDLE / WARNING / DAMAGING. 화로 단계 밖은 IDLE/남은 시간 0. 일시정지 중 타이머 정지.
- BossView: entityId, health/maximumHealth, posture/maximumPosture, state, remainingTicks. Posture: NORMAL / COLLAPSED / IMMUNE. 없는 보스는 목록에 넣지 않음.
- ExitReason: VOLUNTARY / KICKED / RECONNECT_EXPIRED. MemberExit: status, runId, characterId, revision, returnPending. ExitStatus: REMOVED / ALREADY_REMOVED / STALE_REVISION / NOT_PARTICIPANT / WRONG_PHASE.
- S07의 개인 귀환은 기존 pending return/귀환 ACK 경로를 확장하며 새 성공 ACK를 만들지 않습니다. DB 실패 시 제거 결과를 확정하지 않습니다.

protocol 34·codec·네트워크 수신·HUD·파티 서비스·V040/V041은 Grok 소유입니다. S01은 protocol 33을 변경하지 않습니다. 화면 경로: F6 → 공략 상태/파티 메뉴, 화로 우클릭 → 채널 HUD, 보스 전투 → 자세 HUD, F6 → 파티 → 개인 이탈/투표. 현재 이 새 화면 경로는 미연결이며 S01 전체 화면 완료로 보고하지 않습니다.

## 검사와 종료 조건

`verifyP4ContentContracts`: 승인 수치, 잘못된 데이터 거절, frozen 값 독립성, P3 보존, 불변 snapshot. 기존 `verifyContentCatalog`, `verifyRunContentSnapshot`, `verifyProtocolVersion`과 공유 잠금 단일 전체 회귀를 실행합니다. S01 서버 계약 커밋 후 god를 통해 Grok에 전달하고 Pam/공유 계약 Creed 검토를 요청합니다. 사람 플레이 7항목은 별도 대기입니다.
