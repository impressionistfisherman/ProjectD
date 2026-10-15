# M13b 인증 인수 메모

기준 커밋 `42974c8`. 읽기만 했다. 코드, 서버 설정, 데이터베이스는 바꾸지 않았다. 승인된 개발용 offline 설정은 결함으로 보지 않는다. 정식 인증으로 바꿀지는 출시 전 사람 결정이다.

## 현재 계정 키

달마루 코드는 인증 방식을 고르지 않는다. 접속한 플레이어의 UUID를 그대로 계정 키로 쓴다.

- `server-project/src/main/java/kr/dalmaru/network/DalmaruNetworking.java:161` 에서 `joinedPlayer.getUUID()`를 읽고, `:167` 에서 `AccountRepository.openSession`에 넘긴다.
- `server-project/src/main/java/kr/dalmaru/account/AccountRepository.java:29` 가 그 UUID로 `dalmaru.accounts`를 만들거나 찾는다.
- `server-project/database/migrations/V001__accounts_and_characters.sql:11` 계정 표에는 UUID, 활성 캐릭터, 접속 세대만 있다. 플레이어 이름 칸은 없다.
- 이 커밋의 추적 파일에는 `server.properties`가 없다. `online-mode`를 읽거나 쓰는 코드도 없다. 라이브 서버 설정 파일은 열지 않았다.

UUID를 이름에서 만들지, 계정 서비스에서 받을지는 게임 서버 설정이 정한다. 그 값은 이 저장소 밖에 있다.

## 이름 변경

설계 `docs/TECHNICAL_DESIGN.md:99` 는 플레이어 이름 변경을 계정 변경으로 보지 않고, 인증 UUID를 쓰라고 한다.

- 계정 표는 이름을 저장하지 않는다. 검색에서 캐릭터 `display_name`을 나중에 고치는 코드는 찾지 못했다.
- 캐릭터 이름은 만들 때만 저장한다. `CharacterRepository.java:37` 이 이름을 정규화하고, `:43` 에서 넣는다. `V001__accounts_and_characters.sql:26` 은 정규화 이름을 전역에서 하나로 묶는다.
- 플레이어 이름은 계정 키가 아니다. 파티 초대는 `DalmaruNetworking.java:749` 에서 지금 접속 중인 사람을 이름으로 찾고, `:751` 에서 UUID로 초대한다. 잔류 개체 표시 이름은 `RunRuntime.java:246` 이다.

이름 변경을 실행해 보지는 않았다. 서버가 이름 변경 뒤에도 같은 UUID를 주면 기존 계정으로 들어온다. 이름에 묶인 UUID를 주면 다른 계정이 된다. 어느 쪽인지는 이 커밋의 파일만으로 확정하지 못했다.

## 접속 잠금

설계 `docs/TECHNICAL_DESIGN.md:95` 는 접속 세대가 끊긴 옛 연결의 쓰기를 막아야 한다고 한다. 코드는 이렇게 되어 있다.

- `AccountRepository.java:34` 는 접속마다 `connection_generation`을 1 올린다.
- 세션 데이터베이스 작업은 `DalmaruNetworking.java:61` 의 한 스레드에서 이어 실행한다.
- 새 접속은 `:163` 에서 같은 UUID의 연결 상태를 교체한다. 비동기 결과가 돌아올 때 `:63` `currentJoin`이 그 연결이 아직 현재 접속인지 확인한다. 아니면 그 결과는 버린다.
- 끊김 처리 `:193` 은 지금 연결이 아닌 옛 핸들러면 연결 상태를 지우지 않고 돌아간다.
- 캐릭터 선택 `CharacterRepository.java:261`, 캐릭터 생성 `:401`, 요청 저널 `RequestJournal.java:53` 은 계정 UUID와 세대가 둘 다 맞을 때만 진행한다. 아니면 stale session으로 거부한다.

두 클라이언트로 재접속을 실행하지는 않았다. 위 내용은 소스에서 읽은 것이다.

## 정식 인증으로 바꾸기 전에

1. 사람이 출시 설정을 정한 뒤에만 서버의 인증 방식을 바꾼다. 이 저장소 커밋에는 그 스위치가 없다.
2. 이미 쌓인 계정 UUID가 새 인증 UUID와 같은지 확인한다. 다르면 기존 캐릭터는 따라오지 않는다. 이전 방법은 따로 정해야 한다.
3. 이름을 바꾼 뒤 같은 사람이 같은 `account_id`로 들어오는지 확인한다. 캐릭터 이름 유니크와는 별개다.
4. 같은 계정의 두 번째 접속이 세대를 올리고, 첫 접속의 쓰기가 stale session으로 거절되는지 확인한다. 옛 끊김 처리가 새 연결 상태를 지우지 않는지도 본다.
5. 캐릭터 이름 변경 기능은 아직 없다. 계정 인증 전환과 묶지 않는다.

## 하지 않은 것

게임 접속, 라이브 서버, 운영 데이터베이스, 설정 파일 변경, 이름 변경 실험, 중복 접속 실험은 하지 않았다.
