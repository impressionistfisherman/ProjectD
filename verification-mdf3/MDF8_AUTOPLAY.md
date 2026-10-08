# MDF-8 실제 서버 봇 검증 (2026-10-09 KST)

대상: oscar/mdf8-solo-clear-20261008, 99849859b270bb6984d5379275ce02395353a871.
판정: 요청된 서버 경로 확인 완료. **명령 우회 없는 1인 완주는 미검증이며 수동 필요**입니다. 제품 코드는 변경하지 않았습니다.

## 준비
- tools/autoplay/server-run.js·bot.js를 임시 폴더에 복사해 실행했습니다. 기존 로그를 덮어쓰지 않았습니다.
- 비밀번호는 승인 메시지에서 읽어 실행 프로세스 환경변수로만 전달했습니다. 비밀·online-mode·server.properties 수동 변경 없음.
- 새 시험 계정/캐릭터 M8Oscar_8bcc51, 캐릭터 2a35ad38-f44c-4f78-820c-842333639582. DB fixture로 레벨 15, 힘40·체력20·정신10, 전사 및 +0 명시적 장비 8개를 EQUIPPED에 넣었습니다. 기존 계정/장비는 변경하지 않았습니다.
- 양손 도검, heavy head/body/arms/feet, neck/ring/charm을 장착했습니다. 초기 준비 때 arms를 hands로 잘못 넣어 HP36.5가 관측됐고 정의와 대조하여 fixture만 arms로 고친 뒤 HP37을 확인했습니다.

## 결과
| 항목 | 판정 | 관측과 한계 |
|---|---|---|
| 접속·생성·선택·F6 입장 | PASS — 실제 서버/봇 | 프로토콜27, 생성/선택/입장 accepted. 장비는 DB fixture로 준비 |
| 장비 HP 및 일반 부활 | PASS — 실제 서버/봇 | 최대 HP37(740 RPG), 철문수장 자연 사망 직후 로그 HEALTH12.9. 기대37×35%=12.95와 로그 소수1자리 범위에서 일치. 뒤에는 자연 회복이 진행됨 |
| F1: 장비 조회가 부활을 가로지름 | PASS — 서버측 검증 | 장비 테이블 잠금으로 대기 쿼리1 확인 → 같은 캐릭터 재선택으로 장비 조회 → 명령 사망/봇 부활 → 잠금 해제. 새 접속 entity 최대 HP34→37 확인. 부활 직후 HEALTH11.9(34×35%). 회복 틱으로 관측 Health13.4→15.959804/16.12647 등이 섞여 응답 후 정확한35%는 별도 측정하지 않음 |
| 실제 철문수장 HP | PASS — 서버측 관찰 | RunRuntime이 생성한 보스 max/Health540(10,800 RPG). HP를 바꾸지 않음 |
| 실제 원혼봉수장 HP | PASS — 서버측 관찰 | 화로4초 유지 뒤 생성된 보스 max/Health720(14,400 RPG). HP를 바꾸지 않음 |
| 두 보스 처치·완료 경로 | PASS — 서버측 검증 | 플레이어를 원인으로 한 console damage1000000으로 두 보스 처치. NoAI·위치 격리, 시험 공간 air 처리, 일부 creative 전환·teleport 사용. 실제 전투 완주 PASS가 아님 |
| 보상·주간 횟수 | PASS — 서버측 검증 | 완료 run 9118755f-53b5-444f-a1f5-f2a7191c0d47: COMPLETED, 70전·연철석3·봉수패1·상자1, grant1·원장1·메일1·주간count1 |
| 완료 요청 재전송 | PASS — 서버측 repository/DB 검증 | 실제 서버가 만든 COMPLETE 요청으로 RunRepository.finish 호출: replayed=true, 새 requestId 중복 완료는 거부. 완료 보스에 추가 damage 시도는 No entity. 보상·원장·주간 횟수·경험치 스냅샷 모두 동일 |
| 실패 공략 보상 | PASS — 서버측 검증 | 이전 시험 run2개 FAILED·grant0. 첫 공략은 보스 자연 사망5회 후 명령으로6번째 사망, 두 번째는 지형 질식5회 후 명령으로6번째 사망. 완료 전 clear_count0 |
| 경험치 | 관측 — 서버측 검증 | 최종 XP550: 좀비 보스 처치25씩2회 + 공략 완료500. 재전송 후550 그대로. 완료 보상500만의 총증가라고 해석하지 않음 |

## 발견 결함과 수동 필요
- **FAIL: 발판 지형 간섭.** 두 번째 공략 spawn(89520.5,101,135312.5)에서 반복 질식했습니다. BeaconTowerMap은 바닥/벽을 만들지만 기존 내부 지형을 비우지 않습니다. 제품 수정은 이번 검증 범위 밖이며 시험 발판 주변만 air로 격리했습니다. 자동·수동 완주의 장애가 될 수 있습니다.
- 기본 봇은 회피·방어·기술·물약 전략 없이 보스에게 연속 사망합니다. 정상 장비 상태에서 명령 우회 없는 처치·공략 시간·정밀 피해 수치는 **수동 필요/미검증**입니다.
- F1의 현재 entity 장비 적용은 실제 DB/부활로 확인했으나, 응답 직후 체력의 정밀35% 유지값은 회복 간섭 때문에 수동 계측이 필요합니다.
- 수련 보스·다인·전체 회복/보호막·치명타 RNG·발사체 정합성은 이번에 검증하지 않았습니다.
- 시험 캐릭터/장비/DB 공략3건과 시험 지역은 증거로 남겼습니다. creative 해제 명령은 봇 종료와 겹쳐 No player was found였으므로, 이 fixture로 수동 시험을 재개할 때 survival 상태부터 확인해야 합니다.

## 정리 및 증거
- 봇/서버 exit0, 자체 Gradle daemon 종료 후 Java0·자체 Node0. DB 잠금 프로세스3개 exit0.
- 실패 공략 보스2개를 고유 태그로 정리하고 임시 forceload2개를 해제했습니다. server.properties 설정값 해시 기동 전후 동일.
- 증거: MDF8_AUTOPLAY_RESULT.json. 임시 원본 로그: C:\Users\gusdl\AppData\Local\Temp/oscar-mdf8-live-v92vN2/logs/server.console.log 및 M8Oscar_8bcc51.log.
- 최초 autoplay 검증에서는 제품 코드를 변경하지 않았습니다. 아래 지형 수정 단계에서 코드와 이 검증 문서/JSON을 한 커밋으로 기록합니다. TEST_RESULT.md 및 기존 사용자 파일은 제외하고 push는 하지 않습니다.

- 실행 중 생성된 루트 %SystemDrive%/ProgramData/Microsoft/Windows/Caches의 DB 캐시3개는 자동 승인 검토가 재귀 삭제를 정책 차단으로 거부하여 남겼습니다. 제품 파일은 아닙니다.

## 지형 질식 수정 및 재검증 (2026-10-09 KST)

이 절은 앞선 지형 질식 FAIL의 후속 결과입니다. BeaconTowerMap.build가 바닥/벽/기둥/문을 놓기 전에 내부 x=-8..8, z=-6..74, y=1..4를 AIR로 비우도록 수정했습니다. 기존 바닥·벽·기둥·문은 그대로 생성하며 공간 외부는 비우지 않습니다. 지도 생성/재생성 시 적용됩니다.

- PASS: compileJava compileClientJava testClasses verifyBeaconTowerTerrain --no-daemon --console=plain, exit0. 제품 지도 생성 코드를 그대로 컴파일해 돌/지형으로 채운 대역 공간에서 통로·spawn이 비워지는지, 바닥/벽/기둥/문·외부 지형이 유지되는지 검사했습니다. 이전 HEAD의 생성 코드는 같은 시험에서 기대 assertion 실패로 결함을 재현했습니다.
- PASS — 서버측 spawn 검증: 기존 봇 M8Oscar_8bcc51의 모드가 처음에는 creative(1)임을 확인한 뒤 survival(0)으로 전환했습니다. 새 공략 1636af21-382e-4605-9d95-8e2fe70cb8da의 spawn(252720.5,101,230128.5)에서 74.1초 관찰, 질식/사망0, Health18.626469 유지, 발/머리 AIR 확인. console fill/setblock·수동 air 격리·발판 teleport를 사용하지 않았습니다. 보스 공격과 질식을 분리하기 위해 해당 보스만 NoAI로 두었으므로 무보조 전투 완주 검증은 아닙니다. 관찰 후 NoAI를0으로 원복하고 survival 상태로 종료했습니다.
- 새 시험 공략은 logout 후 PAUSED, 이번 공략 보상0. 기존 XP550/보상grant1 유지. 미완료 fixture는 다음 서버 실행 시 기존 일시정지/만료 규칙을 따릅니다.
- 서버 지도 생성 중6095ms 뒤처짐 경고1회가 있었습니다. 생성 성능의 원인은 분리 측정하지 않았습니다.
- 서버/봇exit0, 자체Java/Node0, 설정값 해시 동일, TEST_RESULT.md 불변, push 없음. 증거: MDF8_AUTOPLAY_TERRAIN_RESULT.json, 로그 C:\Users\gusdl\AppData\Local\Temp/oscar-mdf8-terrain-qyMu4e/logs. 실제 정상 전투 완주·정밀 체력 비율은 여전히 수동 필요입니다.

캐시 삭제는 하지 않았습니다. 남은 경로(사람 판단):

- C:/Users/gusdl/OneDrive/Desktop/Dalmaru/%SystemDrive%/ProgramData/Microsoft/Windows/Caches/cversions.2.db
- C:/Users/gusdl/OneDrive/Desktop/Dalmaru/%SystemDrive%/ProgramData/Microsoft/Windows/Caches/{6AF0698E-D558-4F6E-9B3C-3716689AF493}.2.ver0x0000000000000001.db
- C:/Users/gusdl/OneDrive/Desktop/Dalmaru/%SystemDrive%/ProgramData/Microsoft/Windows/Caches/{DDF571F2-BE98-426D-8288-1A9A39C3FDA2}.2.ver0x0000000000000001.db
