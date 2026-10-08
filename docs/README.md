# 달마루 공식 문서 색인

> 폐봉수대 구현 전 기준: [폐봉수대 콘텐츠 제작 계획](CLOSED_BEACON_TOWER_CONTENT_PLAN.md)

> 문서 ID: IDX-001<br>
> 상태: 기준<br>
> 적용 기준: 2026-09-23

게임 가제는 **달마루**, 개발용 식별자는 `MCRPG`를 사용한다. 정식 게임명은 추후 결정한다. 문서의 상태·효력·변경 기준은 [문서 관리 기준](DOCUMENT_CONTROL.md)을 따른다.
현재 요청에 해당하는 행에서 1~3개 문서의 관련 절만 선택한다. 전부 읽지 않는다.

| 작업 | 기준 문서 |
|---|---|
| 현재 상태·다음 작업·Active Spec | [PROGRESS](../PROGRESS.md) |
| 요구사항 추적·완료 조건 | [REQUIREMENTS](REQUIREMENTS.md) |
| 성장·전투 모드·던전·부활·파티·세계 | [GAME_DESIGN](GAME_DESIGN.md) |
| 직업·무기·승급·스킬 | [CLASSES_AND_SKILLS](CLASSES_AND_SKILLS.md) |
| 전투 계산·장비·강화·재련·경제 | [BALANCE_AND_ECONOMY](BALANCE_AND_ECONOMY.md) |
| 생활 직업·제작·농사·거처·상점 | [LIFE_AND_HOUSING](LIFE_AND_HOUSING.md) |
| 개발 환경·통신·저장·복구 | [TECHNICAL_DESIGN](TECHNICAL_DESIGN.md) |
| 확정 규칙·예외·운영 처리 | [RULES_AND_OPERATIONS](RULES_AND_OPERATIONS.md) |
| 1~20 성장·스킬·장비·폐봉수대 수치 | [PILOT_CONTENT_SPEC](PILOT_CONTENT_SPEC.md) |
| 콘텐츠 ID·저장·통신 계약 | [DATA_CONTRACTS](DATA_CONTRACTS.md) |
| 시험 운영 범위·개발 순서 | [PILOT_AND_DECISIONS](PILOT_AND_DECISIONS.md) |
| 설계·인계·검증·문서 관리 | [DEVELOPMENT_WORKFLOW](DEVELOPMENT_WORKFLOW.md) |
| 구현 요청 작성 | [CODEX_HANDOFF](CODEX_HANDOFF.md) |

## 문서 사용 원칙

- 요구 범위와 완료 조건은 [요구사항 정의서](REQUIREMENTS.md), 실제 구현 상태는 [현재 상태](../PROGRESS.md), 시험 증적은 [검증 결과](../TEST_RESULT.md)를 기준으로 확인한다.
- 게임 수치·규칙·데이터 계약이 겹치면 [문서 관리 기준](DOCUMENT_CONTROL.md)의 우선순위를 적용하고, 충돌을 임의로 통합하지 않는다.
- 문서 수정 시 영향받는 요구사항 ID, 적용 범위, 검증 또는 미검증 사유를 남긴다.

실제 동작은 코드·시험 결과, 의도한 동작은 위 담당 설계 문서가 기준이다. 생활 상세는 LIFE_AND_HOUSING이 담당한다.
과거 문서는 `archive/`에 보존하며 기본 조회에서 제외한다. 문서별 내용은 복제하지 않고 참조한다.
