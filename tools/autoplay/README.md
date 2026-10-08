# 폐봉수대 자동 검증

범위: F6 호출 경로 → 실제 로컬 서버의 입장·전투·화로·보상 → 성공/실패 주간 횟수 DB 증거.
결과: ../../verification-mdf3/F6_AUTOPLAY.md (2026-10-08 실서버 1인 루프 완료, 보스 처치 2회는 서버측 검증).

사용법 (tools/autoplay에서):
1. `server-project/run/mods/dalmaru-0.1.0.jar`를 치운다(있으면 구 코드가 로드된다).
2. `DALMARU_DB_PASSWORD=... node server-run.js` — runServer, 콘솔은 logs/server.console.log, 명령은 `echo <cmd> >> logs/server.cmd`.
3. `node bot.js F6Bot` — 명령은 `echo <cmd> >> logs/F6Bot.cmd` (list/create/select/enter/goto/fight/stop/pos/scan/quit).
4. `./db.sh "SQL"` — 읽기 전용 DB 조회(search_path는 dalmaru).
5. 종료: `quit`, `stop`, `gradlew --stop`, JAR 원위치.
deps/·node_modules/·displaced/는 Git에서 제외한다.
