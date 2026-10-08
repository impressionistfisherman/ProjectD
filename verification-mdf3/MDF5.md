# MDF-5: 소스 추적·개발 JAR 충돌·TRAINING 검증

최종 상태: **COMPLETE — 수정 2건 및 수련 검증 완료. 보스 처치는 서버측 검증으로 구분합니다.**

## 수정 커밋

- 브랜치: `oscar/mdf5-runtime-training-20261008`
- 수정 커밋: `94a3406819ec38e202bc8b1999fc4739bcfe6858`
- 공유 `main` HEAD와 기존 인덱스/스테이징은 보존했습니다. 별도 임시 인덱스로 관련 파일만 커밋했습니다. Git 작성자는 로컬 Oscar 식별자이며 전역 Git 설정은 바꾸지 않았습니다.
- 기존 구현의 다른 스테이징 파일은 포함하지 않았으므로, 실행 검증은 공유 작업본에서 수행했습니다. 이 브랜치가 나머지 미커밋 구현까지 포함하는 독립 배포본이라는 의미는 아닙니다.

## 항목별 결과

| 항목 | 결과 | 수정·근거 |
|---|---|---|
| run 소스까지 무시되는 규칙 | PASS | `.gitignore`의 `run/`을 `/run/`으로 변경. 런타임은 계속 무시, Java run 패키지 10개는 무시되지 않으며 수정 브랜치에 추적됨. `mdf5/ignore-check.json` |
| 구 배포 JAR이 개발 코드를 가림 | PASS | 원본을 삭제하지 않고 `tools/autoplay/displaced/mdf5/`에 보관, SHA-256 일치. 래퍼가 달마루 JAR 발견 시 서버 기동/로그 초기화 전에 거부. 실제 서버는 43 mods로 기동. `deployed-jar-quarantine.json`, `deployed-jar-guard.json`, `server.console.log` |
| TRAINING 모드 | 아래 단계별 결과 | 실제 봇 패킷·실패 경로·DB 확정 확인. 두 보스 처치는 콘솔 피해 명령을 사용한 서버측 검증이며 실전 처치 PASS가 아님 |

변경 파일: `server-project/.gitignore`, `server-project/README.md`, `tools/autoplay/server-run.js`, `server-run.test.js`. 기존 `src/main/java/kr/dalmaru/run/`의 10개 파일은 내용을 수정하지 않고 추적에 포함했습니다. PROGRESS/TEST_RESULT에는 이번 변경·검증 요약만 추가했습니다.

## 개발 실행 재발 방지

`runServer`의 개발 클래스와 `run/mods/dalmaru-0.1.0.jar`가 함께 발견되어 MDF-4에서는 구 코드가 실행됐습니다. 개발 실행 중에는 달마루 배포 JAR을 런타임 밖에 둡니다. 빌드 결과를 개발용 run/mods에 다시 복사하지 않습니다. 삭제 대신 보관하면 배포용 원본을 잃지 않습니다.

`node tools/autoplay/server-run.js`는 중복 JAR을 사전 차단합니다. Gradle을 직접 호출할 때도 README의 동일한 사전 확인 절차를 따릅니다. 원본 JAR 보관 경로·해시는 `mdf5/deployed-jar-quarantine.json`에 있습니다.

## TRAINING 실서버 검증

- 기존 MDF-4 시험 계정 F6Bot/캐릭터 `088a4de9-5b5e-4609-8620-21232aeca805`(60레벨, 초행 최초 완료 있음)을 재사용했습니다. 새로운 레벨 변경·DB fixture 직접 수정은 하지 않았습니다.
- `bot.js`가 실제 로컬 서버에 접속하여 Hello/Welcome 27, 캐릭터 선택, F6와 같은 `run_enter_request`를 전송했습니다. 화면의 F6 키 입력 자체는 테스트하지 않았습니다.
- 입장 시 주간 TRAINING 행은 0이며 난이도는 TRAINING, 파티/보정은 1인입니다.

| 단계 | 결과 | 근거 |
|---|---|---|
| 프로토콜·캐릭터 선택·수련 입장 | PASS (실제 패킷) | `F6Bot.log`, `training-enter.json`, `db-training-active.json` |
| 실제 전투 사망 4회, 부활 3회 뒤 실패 | PASS | 첫 run `972ca9ac-7e53-4f3f-8fa2-449655763d06`가 FAILED. `F6Bot.log`, `server.console.log` |
| 실패 시 주간 횟수·보상 미소모 | PASS | TRAINING clear_count 0, 해당 run 보상 0. `db-training-failed.json` |
| 철문수장 처치 | 서버측 검증 | `execute as F6Bot at @s run damage ... minecraft:player_attack by F6Bot` → 실제 처치 이벤트 → 봉수로 정화 메시지. `server.cmd`, `F6Bot.log` |
| 쇠창살 통과·화로 근처 4초 유지·최종 보스 등장 | PASS (실제 이동/서버 tick) | `training-success-driver.json`의 brazierArrival/finalBoss, `F6Bot.log` |
| 원혼봉수장 처치 | 서버측 검증 | 같은 콘솔 피해 명령으로 실제 보스 처치 이벤트 호출. 실전 피해/생존 균형 PASS가 아님 |
| 완료·마지막 쇠창살 통과·보상 구역 도착 | PASS (이동/DB), 보스 처치 우회 포함 | 성공 run `8af873be-c423-4ad6-b981-bac808485ee2`가 COMPLETED. driver의 completion/rewardArrival |
| 수련 주간 횟수 증가·1회 보상 저장 | PASS (DB) | clear_count 1, 보상 1행: 100전/연철석5/봉수패2/20레벨 상자. `db-training-completed.json` |
| 실제 지갑·재료·우편·최초 보상 기록 | PASS (DB) | 지갑 합계 170전(기존70+수련100), 수련 최초 보상 1회, 우편 2건(기존 초행+수련). `db-training-receipt.json` |

완료 경로 준비 중 대기한 두 번째 입장도 보스에게 사망하여 FAILED가 됐습니다. 이 시도 역시 횟수/보상을 소모하지 않았고, 세 번째 입장을 연속 자동 진행해 완료했습니다. 새로운 TRAINING run 3건은 감사 증거로 DB에 남겨 두었습니다.

## 실행한 검증

- `git check-ignore --no-index`: 런타임 exit 0(무시됨), 소스 10개 exit 1/출력 없음(무시되지 않음).
- `node --check tools/autoplay/server-run.js`: exit 0.
- `node tools/autoplay/server-run.test.js`: exit 0. 임시 디렉터리에 실제 래퍼와 가짜 배포 JAR을 놓고 서버/로그 생성 전에 거부되는지 확인합니다. `guard-test.out.log`, `guard-test.err.log`.
- 실제 `runServer` 경유 컴파일/기동·봇 실행: 서버 exit 0, 봇 exit 0. 변경 없는 과거 Java DB 검증 3개는 반복하지 않았습니다.
- DB 조회·Node assert로 실패/완료 상태, 보상 수치, 주간 횟수, 지갑/최초 보상/우편을 확인했습니다. SQL 조회의 괄호·문자 인코딩 오류는 바로잡아 최종 JSON 증거를 다시 확보했습니다. 게임 결함으로 분류하지 않습니다.
- 봇 quit/서버 stop 뒤 해당 wrapper의 자식 Gradle 데몬도 종료했습니다. 마지막 Java 프로세스 수 0. `process-cleanup.json`, `checks.json`.

## 제약·QA 인계

- `online-mode`, 시크릿 등 서버 속성 값은 전후 동일합니다. server.properties를 수동 수정하지 않았으며, Minecraft가 갱신한 주석 타임스탬프 때문에 파일 전체 해시는 다릅니다. `server-settings-check.json`. 보고서에 DB 비밀번호를 기록하지 않았습니다.
- 보스의 바닐라 체력 상한·기본 공격 수치 등 MDF-4에 기록된 전투 제약은 이번 범위에서 변경하지 않았습니다. 1인 실전 보스 처치, 20레벨 경계 조건 전부, 멀티플레이·서버 재시작·완료 뒤 입장 패킷 재전송은 이번에 검증하지 않았습니다.
- QA: Changsik을 통해 Jim에게 무시 규칙/수정 브랜치의 소스 10개 추적, preflight 테스트, 실패 4회 및 수련 보상/횟수 증거를 독립 확인하도록 인계합니다. Jim의 결과를 대신 주장하지 않습니다.
