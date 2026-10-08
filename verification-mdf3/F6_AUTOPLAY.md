# 폐봉수대 F6 자동 플레이 검증

- 시각: 2026-10-08 17:45~18:30 KST (worker-oscar-f6h)
- 최종 상태: **1인 F6 루프 실서버 검증 완료 — 실패 경로·성공 경로 모두 DB 확정까지 확인**
- 결함 4건을 최소 수정 후 재검증했습니다. 다만 보스 처치 2회는 실제 전투만으로 불가능해(아래 "설계 차이") 서버측 검증으로 처리했습니다.
- 이전 시도(08:40Z)의 BLOCKED 원인은 샌드박스 권한이었고, 이번 환경에서는 재현되지 않았습니다.

## 환경

- 로컬 `server-project` `runServer`(Minecraft 26.1.2, Fabric 0.19.5, dev classes, 43 mods), online-mode=false
- 봇: `tools/autoplay/bot.js`(mineflayer 4.39, 오프라인 계정 `F6Bot`). F6 화면과 같은 `dalmaru:*` 페이로드를 직접 전송합니다.
- DB: localhost:15432/ProjectD. 조회는 `tools/autoplay/db.sh`가 담당하며 읽기 전용입니다. 비밀번호는 환경변수로만 전달했습니다.
- 서버 래퍼: `tools/autoplay/server-run.js`. 콘솔 로그와 stdin 명령 파일을 다룹니다.

## F6 실제 호출 경로 (정적 확인)

`DalmaruClient.java:26,145` F6 → `RunEnterRequest` → `DalmaruNetworking.java:420` → `RunRepository.enter` → `RunRuntime.enter`. 봇은 마지막 두 단계를 같은 패킷(`dalmaru:run_enter_request`)으로 호출했습니다.

## 단계별 결과

| # | 단계 | 결과 | 관찰/증거 |
|---|---|---|---|
| 1 | Hello/Welcome(프로토콜 27) | PASS | `tools/autoplay/logs/F6Bot.run1-prefix.log` |
| 2 | 캐릭터 생성·선택·목록 | PASS (결함1 수정 후) | 수정 전 목록이 항상 "요청이 너무 빠릅니다": `F6Bot.before-fix.log`, `server.before-fix.console.log` |
| 3 | 레벨 1 입장 거부 | PASS | "공략 입장 조건을 충족하지 못했습니다." `F6Bot.run1-prefix.log` |
| 4 | 레벨 60 설정 | 서버측 검증 (fixture) | 콘솔 `execute as F6Bot run dalmaru xp 100000` |
| 5 | F6 입장 → DB 예약·맵 생성·텔레포트 | PASS | run 935feabf… ACTIVE, 주간 행 count 0, 봇이 발판(211664,101,281104)으로 이동 |
| 6 | 접속 종료 → PAUSED, 재접속 → ACTIVE·복귀 | PASS | DB paused_at 설정→해제, `F6Bot.run1-a.log` |
| 7 | 실제 전투에서 보스가 봇을 처치 | PASS | "F6Bot was slain by 철문수장" (`server.run4-final.console.log`) |
| 8 | 부활 체력 35% | PASS (결함3 수정 후) | 사망 직후 HEALTH 358.4 = 0.35 × 최대 1024. 수정 전 7.0(=20×0.35) |
| 9 | 부활 5회 소진 → 6번째 사망 실패 | PASS (결함2 수정 후) | 18:19:16~35 사망 6회 → "부활 횟수를 모두 사용해 폐봉수대 공략에 실패했습니다." 수정 전 7회 사망에도 ACTIVE: `F6Bot.run1-b-noFail.log` |
| 10 | 실패 시 횟수 미소모·보상 없음 | PASS | `logs/db.fail-path.txt`: 935feabf FAILED, clear_count 0, run_reward_grants 0건 |
| 11 | 새 F6 입장(같은 주) | PASS | run cee9cef1… ACTIVE "폐봉수대 공략을 시작했습니다." |
| 12 | 철문수장 실제 타격 | PASS | 보스 HP 1024→1011.96 (`F6Bot.run4-final.log` BOSS 줄) |
| 13 | 철문수장 처치 | 서버측 검증 | 콘솔 `execute as F6Bot … damage @e[zombie,nearest] 100000 minecraft:player_attack by F6Bot`. 처치자=플레이어 이벤트 경로(`AFTER_KILLED_OTHER_ENTITY`→`bossKilled`)는 실제 경로와 같습니다. → "봉수로를 정화하세요…", 쇠창살 27 개방 |
| 14 | 화로 근처 4초 유지 → 원혼봉수장 등장 | PASS | 봇이 쇠창살을 지나 화로(x±4, z+35) 근처로 이동, 이후 "slain by 원혼봉수장" 확인 |
| 15 | 원혼봉수장 처치 | 서버측 검증 | 실제 타격 기록(HP 1024→1017.98) 후 같은 방식의 콘솔 damage |
| 16 | 완료·보상 1회·주간 횟수 +1·경험치 500 | PASS | "폐봉수대를 완료했습니다. 경험치 500…" / `logs/db.success-path.txt`: cee9cef1 COMPLETED, clear_count 1, run_reward_grants 1건(70전·연철석 3·봉수패 1·equipment_box.level_15), first_rewards FIRST_STEP 1건 |
| 17 | 보상 중복 방지 | PASS (구조 확인) | 완료 직후 encounter 제거·상태 COMPLETED. 보상 행은 run+character당 1건. 재완료 요청 수단 없음 |
| — | 수련(TRAINING) 해금 입장 | 미실행 | 시간·DB 정리 부담으로 생략 |

## 발견 결함과 최소 수정 (게임 로직, 재검증 완료)

1. `DalmaruNetworking.ConnectionState`: 속도 제한 기준값 `Long.MIN_VALUE`에서 `now - last`가 오버플로해 목록·프로브가 항상 제한됐습니다. 초기값을 `System.nanoTime() - RATE_LIMIT_NANOS`로 바꿨습니다.
2. `DalmaruNetworking`: Fabric `AFTER_DAMAGE`는 치명 피해에서 호출되지 않아 부활 차감·실패 확정이 전혀 동작하지 않았습니다. 이 처리를 `AFTER_DEATH`로 옮겼습니다.
3. `CombatRuntime.restoreAfterRespawn`(신규) + 재생성 핸들러: 재생성 엔티티가 프로필 공격력·최대 체력 수정치를 잃어 부활 체력이 20×35%가 됐습니다. 이전 엔티티의 수정치를 옮겨 붙입니다.
4. `RunRuntime.spawnBoss`: 보스에 `setPersistenceRequired()`를 추가했습니다. 플레이어가 사망 화면·월드 스폰(128블록 밖)에 있는 동안 보스가 일반 좀비처럼 디스폰해 사라졌습니다. 수정 전 2회 관찰(`server.run1/run2` 로그 이후 아레나에 보스 없음). 수정 후 run4에서는 보스가 사라지지 않았습니다.

`compileJava` 통과(runServer 경유). 별도 단위 테스트는 추가하지 않았습니다.

## 설계와 구현 차이 (수정 범위 밖, 결정 필요)

- 보스 HP는 설계상 10,800/14,400이지만 실제로는 1024입니다. 바닐라 `max_health` 상한 때문이며, 플레이어 최대 체력도 1024로 잘립니다.
- `CombatCalculator.damage`가 호출되지 않아 플레이어 근접 피해가 바닐라 수치입니다(맨손 약 6/타). 보스는 초당 약 55~75 피해를 줘서 **1인 실전 처치가 불가능**합니다. 13·15단계를 서버측 검증으로 처리한 이유입니다.
- 아레나 z=−6쪽 끝이 열려 있어, 보스 강타 넉백으로 봇이 바깥으로 떨어져 익사했습니다.
- 부활 횟수와 encounter 상태는 메모리에만 있어, 서버 재시작 후 재입장하면 맵·보스가 새로 생성되고 부활 횟수가 5로 초기화됩니다.
- 재접속 시 캐릭터 선택 전에는 전투 프로필이 적용되지 않습니다(최대 체력 20).
- 원혼봉수장 등장 시 안내 메시지가 없습니다.
- 재생성 직후 "moved too quickly" 경고가 납니다. 클라이언트가 월드 스폰 좌표를 보낸 뒤 공략 발판으로 텔레포트되는 순서 때문이며, 기능 영향은 없었습니다.

## 실행 환경 정리

- 사전 결함: `server-project/run/mods/dalmaru-0.1.0.jar`(오늘 15:28 빌드)가 dev classes와 함께 로드돼 구 코드가 실행됐습니다(44 mods). 검증 중에는 `tools/autoplay/displaced/`로 옮겼다가 종료 후 원위치했습니다. SHA-256 d5197238…ffeef 일치를 확인했습니다. **runServer 전에 이 JAR을 제거해야 수정 코드가 적용됩니다.**
- 저장소 결함: `server-project/.gitignore:33`의 `run/` 패턴이 `src/main/java/kr/dalmaru/run/` 소스 패키지 전체를 무시합니다(RunRuntime.java 등 미추적). 패턴을 `/run/`으로 바꿔야 합니다. Git 변경은 하지 않았습니다.
- `server.properties`: 전후 해시는 다릅니다. Minecraft가 기동 시 2번째 줄 타임스탬프를 다시 쓰기 때문입니다. 그 줄을 이전 값 `#Thu Oct 08 17:44:05 KST 2026`으로 되돌리면 이전 해시(a085a390…)와 정확히 일치하므로, 다른 내용(online-mode=false, secret)은 바뀌지 않았습니다.
- 띄운 서버(runServer)·봇·Gradle 데몬 모두 종료를 확인했습니다(`logs/server.exit.txt` 0, 잔존 java/node 없음).
- 테스트 데이터: 캐릭터 088a4de9…(봉수검증, 레벨 60), run 935feabf…(FAILED), run cee9cef1…(COMPLETED). DB에 남겨 두었습니다.

## 증거 파일 (`tools/autoplay/logs/`)

`server.run4-final.console.log`, `F6Bot.run4-final.log`(실패·성공 최종), `db.fail-path.txt`, `db.success-path.txt`, `F6Bot.before-fix.log`, `server.before-fix.console.log`, `F6Bot.run1-*.log`, `server.run1~3.console.log`
