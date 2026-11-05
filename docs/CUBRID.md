# CUBRID 에서 SQLancer 돌리기

SQLancer 가 임의의 스키마·데이터·질의를 만들어 CUBRID 에 보내고, 같은 답이어야 하는 질의들의 결과를 비교해
옵티마이저·실행기의 논리 결함을 찾는다. CUBRID 제공자는 `src/sqlancer/cubrid/` 에 있다(JIRA CBRD-27932).

- 판정 방식(test oracle)은 **TLP WHERE**(Ternary Logic Partitioning) 하나다. 술어 `p` 가 있으면
  `SELECT ... FROM t` 의 행 = `... WHERE p` + `... WHERE NOT p` + `... WHERE (p) IS NULL` 이어야 한다.
  어긋나면 결과 불일치로, 예상하지 못한 오류가 나면 그 오류로 보고한다.
- 만드는 것: 테이블 1~3개(SMALLINT·INTEGER·BIGINT·DOUBLE·VARCHAR 열, NOT NULL·UNIQUE·PRIMARY KEY), INSERT,
  인덱스(UNIQUE·DESC 포함), `UPDATE STATISTICS`, 그리고 타입을 맞춘 식 — 비교·논리·IS [NOT] NULL·IS [NOT]
  TRUE/FALSE/UNKNOWN·BETWEEN·IN·LIKE·산술·CASE·CAST·함수, INNER/CROSS 조인.
- 지금까지 찾은 결함과 재현 SQL 은 CBRD-27932 의 첨부 파일에 있다.

## 1. 준비

| 무엇 | 조건 |
|---|---|
| CUBRID | 설치본과 환경 변수(`CUBRID`, `PATH` 의 `cubrid`·`csql`), 브로커 기동(기본 33000) |
| Java | 11 이상 (`java -version`) |
| Maven | 3.5 이상 — CUBRID JDBC 드라이버는 CUBRID Maven 저장소(https://maven.cubrid.org/)에서 받는다(pom 에 등록됨) |

```bash
git clone -b cubrid https://github.com/soheejung-cs/sqlancer.git
cd sqlancer
mvn -B -q package -DskipTests        # target/sqlancer-2.0.0.jar 와 target/lib/
```

Java 가 여럿이면 `JAVA_HOME` 을 11 이상으로 맞춘 뒤 빌드·실행한다. Java 8 로 빌드하면 Java 버전을 말하지 않는
`PluginContainerException` 으로 실패한다.

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk      # 설치 경로는 장비마다 다르다
export PATH=$JAVA_HOME/bin:$PATH
```

## 2. 풀 DB 만들기

CUBRID 는 SQL 로 DB 를 만들 수 없어서, 작업 스레드마다 미리 만든 DB 하나를 쓴다. 스레드 i 는 `sqlancer<i>`
를 쓰고, 새 DB 를 만들 때마다 그 DB 의 **사용자 테이블과 뷰를 전부 지운다** — 다른 용도의 DB 를 쓰지 않는다.

```bash
export SQLANCER_CUBRID_DB_ROOT=$HOME/sqlancer-pool     # 볼륨을 둘 곳 (기본 ./cubrid-pool)
scripts/cubrid/sqlancer-cubrid-pool.sh create 4        # sqlancer0..3 생성 + cubrid.conf 에 [@sqlancerN] 작은 버퍼
scripts/cubrid/sqlancer-cubrid-pool.sh start 4
scripts/cubrid/sqlancer-cubrid-pool.sh status 4
# 끝나면: stop 4, 다 지우려면 drop 4
```

DB 하나가 데이터 버퍼 128M·로그 버퍼 16M 을 쓴다(서버 하나에 RSS 약 0.5GB). 스레드 수만큼 만든다.

## 3. 실행

```bash
mkdir -p run1 && cd run1               # 로그가 ./logs/cubrid/ 에 쌓인다
java -Xmx2g -jar ../target/sqlancer-2.0.0.jar \
     --num-threads 4 --num-tries 100 --timeout-seconds 7200 --num-queries 2000 \
     --log-each-select true --print-progress-summary true \
     cubrid --oracle WHERE
```

- 공통 옵션(`cubrid` 앞): `--num-threads`(동시 스레드 = 풀 DB 수 이하), `--timeout-seconds`(전체 시간),
  `--num-tries`(시도 수 — 결함을 하나 찾을 때마다 시도 하나가 끝난다), `--random-seed`(재현용),
  `--host`·`--port`(브로커, 기본 localhost:33000), `--username`·`--password`(기본 dba, 암호 없음).
- CUBRID 옵션(`cubrid` 뒤):

| 옵션 | 기본 | 뜻 |
|---|---|---|
| `--oracle` | WHERE | 판정 방식 |
| `--database-pool-prefix` | sqlancer | 풀 DB 이름 앞부분 |
| `--test-joins` | true | 조인을 만들지 |
| `--max-num-tables` | 3 | 만들 테이블 수 상한 |
| `--query-timeout` | 10 | 문장 하나의 시간 상한(초, 브로커가 끊는다) |
| `--test-float` | false | FLOAT 열을 만들지 — 정밀도 차이를 따로 모으는 캠페인에서만 켠다(아래 5) |

- `--num-queries` 는 DB 하나에 던질 질의 수다. SQLancer 기본값 100000 이면 몇 시간을 돌려도 DB(스키마·데이터)가 수십
  개뿐이라, 인덱스·통계·데이터 모양에 달린 옵티마이저 결함을 덜 훑는다. 2000 이면 4스레드에서 새 DB 를 초당 약 0.5개 만든다
  (develop, 2시간 645만 질의·DB 약 3,000개, 초당 약 800 질의).
- `--log-each-select false` 는 쓰지 않는다 — SQLancer 본체가 `logExecutionTime()` 에서 AssertionError 를 던져 바로 멈춘다.

## 4. 결과 읽기

- 보고는 `logs/cubrid/database<N>.log` 에 남는다(`-cur.log` 는 진행 중인 것). 앞부분은 DB 를 만든 문장, 끝의
  `-- On the database set up by the statements above, ...` 아래가 어긋난 질의다.
- csql 로 그대로 다시 돌려 본다(SQLancer 는 오류 메시지의 첫 줄만 남기므로 전문은 csql 로 본다):

```bash
python3 ../scripts/cubrid/replay_log.py logs/cubrid/database3.log case.sql
csql -u dba sqlancer0 -i case.sql      # 캠페인이 쓰지 않는 풀 DB 에서
```

- 결과 불일치: 원 질의 행 수와 세 갈래 행 수의 합을 비교한다. 세 갈래를 따로 돌리면(`UNION ALL` 을 나눠서)
  어느 갈래가 틀렸는지 보인다.
- `No more data`: JDBC 연결이 끊긴 것이다. 그 시각 CAS 프로세스가 살아 있고 csql 로 다시 돌려 정상이면 연결 문제로 본다.
- `Request timed out`: 문장이 오래 걸렸거나, 클라이언트(브로커의 CAS)가 죽어 응답이 없었던 것이다. csql 로 다시
  돌려 csql 이 죽으면 크래시다(코어 덤프 확인).

## 5. 결함과 정밀도 차이를 가른다

CUBRID 는 성능을 위해 NUMERIC 으로 캐스트하고 연산자마다 정해진 변환을 한다. 그 때문에 생기는 정밀도 차이는
결함 목록에 넣지 않고 따로 모은다. 예: FLOAT 과 INT 의 비교는 FLOAT 으로 반올림되어 TLP 가 어긋날 수 있다.
BIGINT 열도 2^53 을 넘는 값이 DOUBLE 경계(`0.5E0` 같은 지수 리터럴)와 비교되면 같은 일이 생긴다.
가르는 법 — 준비 문장의 FLOAT 열을 DOUBLE 로, 또는 DOUBLE 리터럴을 지수 없는 같은 값(NUMERIC)으로 바꿔 다시 돌려 합이
맞으면 정밀도 계열이다. 그래서 FLOAT 열은
`--test-float true` 일 때만 만든다.

## 6. 알려진 결함 스위치

이미 찾은 결함이 캠페인마다 다시 보고되지 않도록 `src/sqlancer/cubrid/CUBRIDBugs.java` 의 스위치가 그 꼴을
피하거나(생성기) 예상 오류로 둔다. 결함이 고쳐진 빌드에서는 그 스위치를 `false` 로 바꿔 고쳐졌는지 확인한다.

| 스위치 | 결함 |
|---|---|
| `setOperationFoldedToNull` | 갈래가 모두 상수 거짓인 집합 연산을 NULL 값으로 접는다 |
| `outerJoinReducedByNullablePredicate` | NULL 에도 참인 술어로 외부 조인을 내부 조인으로 줄인다 — 켜져 있으면 INNER/CROSS 만 만든다 |
| `orderBySkipOnIsFalse` | `(c IS NOT NULL) IS FALSE` 를 NOT NULL 증거로 보고 ORDER BY 를 생략한다 — 켜져 있으면 ORDER BY 를 만들지 않는다 |
| `nullSafeEqualsOfTwoMinuses` | `-x <=> -y` 가 'unknown opcode' 오류 |
| `inListCoercedToFixedLengthElement` | IN 목록을 길이가 정해진 원소의 도메인으로 바꾸다 오류 |
| `caseFoldWithNullBranchCrash` | CASE 상수 접기에서 클라이언트 크래시 — 켜져 있으면 CASE 갈래에 NULL 상수를 넣지 않는다 |
| `likeRuntimePatternMissesEmptyString` | 실행 때 정해지는 LIKE 패턴('', '%')이 빈 문자열 행을 놓친다 — 켜져 있으면 패턴을 리터럴로만 만든다 |

## 7. 주의

- 풀 DB 의 사용자 테이블은 SQLancer 가 지운다. 다른 데이터가 있는 DB 를 풀로 쓰지 않는다.
- CTP 의 shell TC 처럼 `cub` 프로세스를 죽이거나 서비스를 멈추는 시험과 같은 장비에서 동시에 돌리지 않는다.
- 서버 assert 를 암묵적 판정으로 쓰려면 디버그 빌드에서 돌린다(서버가 죽으면 연결 오류로 보고된다).
