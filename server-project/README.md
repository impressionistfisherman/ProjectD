# Example Mod

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.


## 달마루 개발 서버 실행

`runServer`는 `build/classes`의 개발 코드로 실행합니다. `run/mods/dalmaru*.jar`에 배포 JAR이 함께 있으면 이전 코드가 로드될 수 있으므로 개발 실행 전에 반드시 해당 JAR을 `tools/autoplay/displaced/` 같은 런타임 밖 경로에 보관하세요. 빌드 결과를 개발용 `run/mods`에 다시 복사하지 마세요. 원본 JAR은 삭제할 필요가 없습니다.

루트에서 `node tools/autoplay/server-run.js`를 사용하면 중복 달마루 JAR을 서버 기동과 로그 초기화 전에 거부합니다. DB 접속 정보는 환경변수로 전달하세요. Gradle을 직접 실행할 때도 먼저 같은 JAR 부재 조건을 확인해야 합니다.

```powershell
$deployed = Get-ChildItem -LiteralPath ./server-project/run/mods -Filter dalmaru*.jar -ErrorAction SilentlyContinue
if ($deployed) { throw "개발 실행 전 달마루 배포 JAR을 run/mods 밖으로 보관하세요." }
Set-Location ./server-project
./gradlew.bat runServer --console=plain
```

개발 런타임 `server-project/run/`만 Git에서 제외합니다. Java의 `src/main/java/kr/dalmaru/run/`는 추적 대상입니다.
