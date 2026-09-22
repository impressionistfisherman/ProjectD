# 달마루 설계 문서 안내

게임 가제는 **달마루**, 개발용 식별자는 `MCRPG`를 사용한다. 정식 게임명은 추후 결정한다.
현재 요청에 해당하는 행에서 1~3개 문서의 관련 절만 선택한다. 전부 읽지 않는다.

| 작업 | 기준 문서 |
|---|---|
| 현재 상태·다음 작업·Active Spec | [PROGRESS](../PROGRESS.md) |
| 성장·전투 모드·던전·부활·파티·세계 | [GAME_DESIGN](GAME_DESIGN.md) |
| 직업·무기·승급·스킬 | [CLASSES_AND_SKILLS](CLASSES_AND_SKILLS.md) |
| 전투 계산·장비·강화·재련·경제 | [BALANCE_AND_ECONOMY](BALANCE_AND_ECONOMY.md) |
| 생활 직업·제작·농사·거처·상점 | [LIFE_AND_HOUSING](LIFE_AND_HOUSING.md) |
| 개발 환경·통신·저장·복구 | [TECHNICAL_DESIGN](TECHNICAL_DESIGN.md) |
| 시험 운영 범위·미결정 사항 | [PILOT_AND_DECISIONS](PILOT_AND_DECISIONS.md) |
| 설계·인계·검증·문서 관리 | [DEVELOPMENT_WORKFLOW](DEVELOPMENT_WORKFLOW.md) |
| 구현 요청 작성 | [CODEX_HANDOFF](CODEX_HANDOFF.md) |

실제 동작은 코드·시험 결과, 의도한 동작은 위 담당 설계 문서가 기준이다. 충돌하면 위치와 영향을 확인하고 임의로 통합하지 않는다. 생활 상세는 LIFE_AND_HOUSING이 담당한다.
과거 문서는 `archive/`에 보존하며 기본 조회에서 제외한다. 문서별 내용은 복제하지 않고 참조한다.
