# MDF16/J02 4인 통합 시나리오 — 2026-10-13

상태: PASS. 제품 코드 추가 변경 없이 Oscar 직접 검증했습니다. 검증범위 완료이며 독립 Jim QA나 수동 전투 밸런스 결과가 아닙니다.

## 범위
실제 4개 Minecraft 네트워크bot/계정/캐릭터로 READY→ACTIVE, 일반몹 그룹→첫보스→화로→최종그룹/소환→최종보스→완료보상/복귀/cleanup을 실행했습니다. 시험 전용 Fabric addon으로 플레이어 출처의 RPG 피해를 주고 생존을 제어했습니다. 자연스러운 전투 조작/난이도/보스HP·피해 인원배율은 시험하지 않았습니다. addon은 임시폴더에만 두었으며 제품 manifest/설정에 추가하지 않았습니다.

## 결과
- 필수몹 고정24+지휘자 1회 소환2를 실제 개체로 생성·처치했습니다. 실제 requiredKills=26. 별도 정책시험 좀비1마리는 required roster와 구분했습니다.
- G0/1 미처치 시 첫보스·문 진행 없음. G2 미처치 상태에서 두 명이 화로에 4.6초 있어도 진행 없음. 처치 후 한 명만 4.6초 있어도 진행 없음. 두 명의 80tick/4초 조건 충족 뒤 G3/문 열림. 지휘자 HP50% 이하에서2마리 소환, 재소환 없음. G3와 소환체 처치 뒤 최종보스.
- 실제 던전 사망/조기respawn 경로: 같은 구역, 닫힌 문 이전 위치, 부활횟수5→4, HP35%·기력25% 이후회복 확인. 3초 면역 동안 실제 mobAttack RPG피해100이 차단됐습니다. 아래 면역 증거에서 actor invulnerable flag는 false로 만든 뒤 피해를 적용했습니다.
{
  "action": "immunity",
  "actor": "Q16D_0",
  "id": "d321eef2-ff44-4bb3-a794-a2331d437752",
  "immuneHitBlocked": true,
  "immunity": true,
  "stage": "GATEKEEPER",
  "group": 0,
  "mobs": 5,
  "summoned": false,
  "boss": false,
  "revives": 4,
  "x": 1189648,
  "y": 100,
  "z": 1114256,
  "gate27": false,
  "gate45": false,
  "loot": 0,
  "xpOrbs": 0,
  "regionEntities": 5,
  "terrainRestored": false,
  "requiredKills": 0,
  "policyKills": 0,
  "actorHealth": 8.4,
  "maxHealth": 24,
  "actorX": 1189648.5,
  "actorZ": 1114256.5,
  "mode": "survival",
  "stamina": 25,
  "maxStamina": 100
}
- 태그를 붙인 vanilla Zombie에 다이아검 guaranteed drop을 지정해 실제 플레이어 피해로 처치: loot0, vanilla XP orb0, DB필드XP0/좀비quest0. 이는 Husk의 기본필드보상0만 검사한 것이 아닙니다. 일반몹/소환체 전체 처치 중 완료보상grant0 유지.
- 최초보스와 최종보스는 기존 Zombie 경로를 유지합니다. 최종 DB에서 리더 경험치550(보스처치50+완료500)/좀비quest2, 나머지3명 경험치500/quest0. J02 일반몹·소환체 보상차단과 기존 보스경로를 구분합니다.
- Run d321eef2-ff44-4bb3-a794-a2331d437752: COMPLETED,party_size4,scaling_tier4. 완료grant4행, 개인당copper70/ironstone3/beacon_token1/최초level15상자1. 2.2초뒤grant4/통화ledger4행 유지, 개인 주간clear_count1. 최종wallet4명모두70, material.ironstone3/token.bongsu1만 확인.
- 종료 후 regionEntities0, loot0/orb0, 지형복원true, participant return_pending0, 원래마을복귀 확인.

## 증거/경계
- fullrun-result.json/scenes.json/console.log, fullrun.js/QaProbe.java. fixture 봇/계정 및 경로는 현재 시험용이며 재실행시 새로운격리fixture를 사용해야 합니다. 실제실행addon의추가읽기전용bossMax출력은본검증조건에포함하지않았습니다.
- 이번 시나리오 첫 실행 PASS. 전체기존회귀/빌드를 반복하지 않았습니다. 운영PID4936/운영DB/원래JAR/settings/secrets 미변경, 추가commit/push/merge/배포 없음. 운영JAR SHA256 1B180A3C90FEF48521ADD06EB086AC2DF38E6A5D2BCC541BB74A5D6F298E6539 불변.
- 남는범위: 실제키보드/마우스와키재지정, 보호/피해량 shortcut 없는4인수동전투완주와밸런스(보스인원배율포함). 이전client렌더/주입숫자키 및실제packet검사는재사용했습니다.
