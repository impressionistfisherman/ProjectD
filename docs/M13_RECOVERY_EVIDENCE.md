# M13a 백업·복원 훈련 증거

기준: `hive/research/ROADMAP_PHASES.md`의 O43. 목표 상한은 복구 시점 목표(RPO) 5분, 복구 시간 목표(RTO) 30분. 이 문서는 로컬 복사 데이터베이스 1회 훈련 결과다.

## 범위

- 원본 `oscar_p1_fixture` (`localhost:15432`)는 `pg_dump`로 읽기만 했다. 덤프 전후 체크섬이 같았다.
- 복원 대상은 `oscar_p1_restore_drill` 하나다. 만들고, 복원하고, 대조한 뒤 그 데이터베이스만 삭제했다.
- 데이터베이스 생성·삭제만 `postgres` 유지관리 데이터베이스에 접속해 실행했다. 그 안의 테이블은 바꾸지 않았다.
- 운영 데이터베이스, `oscar_fixture`, 라이브 서버, M13b(인증 확인)는 범위 밖이다.
- 덤프 파일은 작업 트리 밖 임시 폴더에 두었다가 훈련 끝에 삭제했다. 커밋하지 않았다.
- 비밀번호는 `DALMARU_DB_PASSWORD`를 `PGPASSWORD`로만 넘겼다. 스크립트·이 문서·출력에 값은 없다.

## 실행 절차

1. `scripts/m13-fixture-recovery.ps1`를 작업 트리 루트에서 실행한다.
2. 스크립트는 원본 34개 테이블의 행 수와 행 텍스트 MD5를 읽는다.
3. `pg_dump --format=custom`으로 원본을 임시 파일에 받는다. 다시 읽어 원본이 안 바뀌었는지 확인한다.
4. `oscar_p1_restore_drill`을 만들고 `pg_restore --no-owner --no-privileges --exit-on-error`로 복원한다.
5. 같은 34개 테이블의 이름·행 수·MD5를 원본과 비교한다.
6. 그 데이터베이스 연결을 끊고 `DROP DATABASE` 한다. 임시 덤프 폴더를 지운다.

도구: `C:\Program Files\PostgreSQL\18\bin`의 `pg_dump`, `pg_restore`, `psql`. 기록된 실행은 2026-10-15 02:37 UTC, 종료 코드 0.

## 실측

| 항목 | 값 | 목표 |
|---|---|---|
| RPO (덤프 스냅샷 뒤부터 덤프가 끝나기까지) | 0.213초 (`02:37:24.120Z`–`02:37:24.334Z`) | 5분 |
| 복원만 | 0.315초 | |
| 대조 | 2.540초 | |
| RTO (데이터베이스 생성부터 대조 일치까지) | 3.393초 | 30분 |

`pg_dump`는 시작 시점의 스냅샷을 담는다. 덤프가 끝나는 순간에 장애가 나면, 스냅샷 이후 덤프 동안의 쓰기가 빠진다. 이번 훈련에서 그 창은 0.213초이고, 원본 체크섬은 덤프 전후가 같았다. 이 수치는 이 로컬 복사본의 1회 덤프·복원이다. 운영 연속 백업을 증명하지 않는다.

판정: 이번 측정은 RPO 5분, RTO 30분 안에 있다.

## 행 수·체크섬

테이블 34개, 행 합계 24. 원본과 복원본의 테이블 이름·행 수·MD5가 모두 같았다. 빈 테이블 33개의 MD5는 빈 문자열의 MD5인 `d41d8cd98f00b204e9800998ecf8427e`이다.

| 테이블 | 행 | MD5 |
|---|---:|---|
| dalmaru.accounts | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_creation_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_currency_ledger | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_enhancement_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_equipment | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_equipment_move_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_inheritance_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_mailbox | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_materials | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_profession_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_professions | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_profiles | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_quest_progress | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_refinement_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_run_first_rewards | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_run_weekly_clears | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_safe_points | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_selection_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_skill_learning_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_skill_loadouts | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_skill_ranks | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_stat_investment_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_stat_reset_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_vanilla_state | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.character_wallet | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.characters | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.operation_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.run_instances | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.run_participants | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.run_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.run_reward_grants | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.schema_migrations | 24 | 881fb6071a3a25a52d5a1bc612d65ecd |
| dalmaru.skill_loadout_requests | 0 | d41d8cd98f00b204e9800998ecf8427e |
| dalmaru.town_safe_zones | 0 | d41d8cd98f00b204e9800998ecf8427e |

## 정리 확인

훈련 직후 조회: `oscar_p1_fixture`만 남아 있고 `oscar_p1_restore_drill`은 없다. 임시 폴더 `dalmaru-m13a-*`는 0개다.
