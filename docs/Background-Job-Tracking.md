# Background Job Tracking

Background Job Tracking: 대용량 배치 및 데이터 내보내기 작업의 처리 스냅샷과 최종 완료 상태를 실시간으로 클라이언트에 피드백함.

## Usecase

```mermaid
graph TD
    %% Actors
    User([요청 사용자<br>Request User])
    Processor[작업 처리기<br>Job Processor]

    %% System Boundary
    subgraph JobTrackingSystem [백그라운드 작업 트래킹 시스템]
        UC1((대용량 데이터 내보내기 요청))
        UC2((실시간 진행 스냅샷 SSE 구독))
        UC3((배치 작업 진행률 모니터링))
        UC4((최종 완료 상태 및 다운로드 링크 수신))
    end

    %% Relations
    User --> UC1
    User --> UC2
    Processor --> UC3
    Processor --> UC4

    UC1 -. "include" .-> UC2
    UC3 -. "include" .-> UC2
    UC4 -. "include" .-> UC2

    %% Styles
    style User fill:#f9f9f9,stroke:#333,stroke-width:2px
    style Processor fill:#f5f5f5,stroke:#333,stroke-width:2px
    style UC1 fill:#fff,stroke:#333,stroke-width:1px
    style UC2 fill:#fff,stroke:#333,stroke-width:1px
    style UC3 fill:#fff,stroke:#333,stroke-width:1px
    style UC4 fill:#fff,stroke:#333,stroke-width:1px
```

## Sequence

```mermaid
sequenceDiagram
    autonumber
    actor User as 요청 사용자 (User)
    participant Server as 모니터링 시스템 (Server)
    participant Processor as 작업 처리기 (Job Processor)

    %% 1. 비동기 배치 작업 및 SSE 구독 시작
    User->>Server: HTTP POST /api/v1/jobs/export (대용량 데이터 내보내기 요청)
    activate Server
    Server-->>User: HTTP 202 Accepted (Job ID: "job-999" 반환 및 비동기 처리 시작)
    deactivate Server

    User->>Server: HTTP GET /api/v1/jobs/job-999/progress (SSE 채널 구독 시작)
    activate Server
    Server-->>User: HTTP 200 OK (text/event-stream 연결 확립)

    %% 2. 배치 진행 스냅샷 스트리밍
    Server->>Processor: 비동기 백그라운드 배치 작업 위임 (Job ID: job-999)
    loop 대용량 데이터 분할 처리 (Chunk 단위)
        Processor->>Server: 현재 처리 스냅샷 갱신 피드백 (진행률 25%, 50%, 75%)
        Server-->>User: SSE Event 푸시 (type: "job-progress", data: "progress: 50%")
        User->>User: 브라우저 UI 프로그레스 바 실시간 진행도 갱신
    end

    %% 3. 최종 완료 상태 및 결과 반환
    Processor->>Server: 최종 파일 생성 완료 알림 (S3 저장소 업로드 성공)
    Server-->>User: SSE Event 푸시 (type: "job-complete", data: "downloadUrl: 'https://...'")
    User->>User: 대시보드 완료 팝업 표출 및 파일 다운로드 링크 활성화
    deactivate Server
```

## Domain model

```mermaid
classDiagram
    class TrackingJob {
        - String jobId
        - String userId
        - JobStatus status
        - JobProgress progress
        - JobResult result
        - LocalDateTime createdAt
        + updateProgress(int processedItems, int totalItems) JobProgressEvent
        + complete(String downloadUrl, long fileSize) JobProgressEvent
        + fail(String failureReason) JobProgressEvent
        - transitStatus(JobStatus newStatus) void
    }

    class JobProgress {
        - int processedItems
        - int totalItems
        - double percentage
        - LocalDateTime lastUpdatedAt
        + calculatePercentage() double
        + isCompleted() boolean
    }

    class JobResult {
        - String downloadUrl
        - long fileSize
        - String failureReason
        - LocalDateTime endedAt
        + hasOutput() boolean
    }

    class JobProgressEvent {
        - String eventId
        - String jobId
        - String eventType
        - double currentPercentage
        - String downloadUrl
        - LocalDateTime occurredAt
    }

    class JobStatus {
        <<enumeration>>
        ACCEPTED
        PROCESSING
        COMPLETED
        FAILED
    }

    TrackingJob "1" *-- "1" JobProgress : monitors
    TrackingJob "1" *-- "1" JobResult : yields
    TrackingJob --> "1" JobStatus : tracks
    TrackingJob ..> JobProgressEvent : outcomes
```


## State Transition

```mermaid
stateDiagram-v2
    [*] --> ACCEPTED : 대용량 데이터 내보내기 요청 접수

    ACCEPTED --> PROCESSING : startProcessing() [첫 번째 청크 분할 처리 개시]
    ACCEPTED --> FAILED : cancelJob() [시스템 리소스 한계 등으로 인한 즉시 거부]

    state PROCESSING {
        [*] --> ChunkProcessing
        ChunkProcessing --> ChunkProcessing : updateProgress() [지속적인 처리 스냅샷 갱신 및 백분율 계산]
    }

    PROCESSING --> COMPLETED : complete(downloadUrl) [모든 데이터 처리 완료 및 S3 업로드 성공]
    PROCESSING --> FAILED : fail(reason) [배치 처리 중 예외 발생 또는 타임아웃]

    COMPLETED --> [*]
    FAILED --> [*]
```

## Policy

**요약 목록**

* **TrackingJob 상태 제약**: 작업이 접수(`ACCEPTED`)되거나 처리 중(`PROCESSING`)일 때만 변경이 가능하며, 최종 상태(`COMPLETED`, `FAILED`)에 도달하면 모든 필드는 불변(Immutable) 상태로 동결됩니다.
* **JobProgress 산출 규칙**: 총 아이템 수 대비 처리된 아이템 수의 무결성을 검증하고, 백분율 진행도(`percentage`)를 항상 0%에서 100% 사이로 제어합니다.
* **JobResult 다운로드 및 실패 정보 검증**: 작업 성공 시 유효한 다운로드 경로 및 파일 크기를 강제하고, 실패 시에는 원인 메시지를 필수로 기록해야 합니다.

---

### 1. TrackingJob (애그리게잇 루트) 규칙 및 유효성 검사

* **상태 기반 행위 가드 규칙**
* `updateProgress()`는 오직 `status`가 `ACCEPTED` 또는 `PROCESSING`일 때만 호출할 수 있습니다. 첫 호출 시에는 상태가 `PROCESSING`으로 자동 전이되어야 하며, 이미 `COMPLETED`되거나 `FAILED`된 작업에 대한 프로그레스 업데이트 시도는 도메인 예외를 발생시켜야 합니다.
* `complete()`와 `fail()` 메서드는 **최종 터미널(Terminal) 상태로의 전이**를 담당하므로, 이미 `COMPLETED` 혹은 `FAILED`인 상태에서는 중복 처리가 불가능하도록 전이 가드를 작동해야 합니다.


* **원자적 도메인 이벤트 발행**
* 진행률 갱신, 최종 완료, 실패 처리 시 생성되는 `JobProgressEvent`는 각 상태 변경 및 결과 데이터 바인딩과 **동시에 원자적으로 생성**되어야 하며, SSE 브로드캐스팅 레이어로 즉시 전달될 수 있는 상태 정보를 완전하게 내포해야 합니다.



### 2. JobProgress (밸류 오브젝트) 유효성 검사 규칙

* **수치적 정합성 검증**
* 총 대상 아이템 수(`totalItems`)는 항상 0보다 큰 양수(`totalItems > 0`)여야 합니다.
* 현재 처리된 아이템 수(`processedItems`)는 0 이상이어야 하며, `totalItems`를 초과할 수 없습니다 (`0 <= processedItems <= totalItems`).


* **진행 백분율 제어 (`calculatePercentage`)**
* `percentage`는 수식 $(processedItems / totalItems) * 100$에 의해 도메인 내부에서 안전하게 계산되어야 하며, 임의의 외부 조작에 의해 100%를 초과하거나 0% 미만으로 떨어지지 않도록 가드합니다.



### 3. JobResult (밸류 오브젝트) 유효성 검사 규칙

* **성공 산출물 무결성 가드 (`complete` 시)**
* 작업이 정상적으로 완료되어 `COMPLETED` 상태로 진입할 때, `downloadUrl`은 절대로 null이거나 비어있을 수 없으며 올바른 URI 포맷을 충족해야 합니다.
* 생성된 파일 크기(`fileSize`)는 0 바이트보다 커야 합니다 (`fileSize > 0`). 성공 상태임에도 파일 크기가 0이거나 URL이 누락되는 논리적 모순을 도메인 수준에서 방단합니다.


* **실패 원인 기록 강제 (`fail` 시)**
* 작업이 예외나 타임아웃으로 인해 `FAILED` 상태로 진입할 때, `failureReason`은 추후 관리자 추적 및 사용자 피드백을 위해 반드시 사유가 기록되어야 하며 빈 문자열을 허용하지 않습니다.