# Social Activity Notification

Social Activity Notification: 타 사용자의 인터랙션 발생 시 이벤트를 발행하고, 대상 사용자의 활성화된 SSE 채널로 알림을 즉시 푸시함.

## Usecase

```mermaid
graph TD
    %% Actors
    TriggerUser([발생자<br>Trigger User])
    TargetUser([대상 사용자<br>Target User])
    ExternalSystem[외부 알림 시스템<br>APNS / FCM]

    %% System Boundary
    subgraph SocialNotificationSystem [소셜 알림 시스템 Social Notification System]
        UC1((인터랙션 수행<br>좋아요/댓글/팔로우))
        UC2((SSE 알림 채널 구독))
        UC3((알림 이벤트 발행))
        UC4((실시간 알림 푸시))
    end

    %% Relations
    TriggerUser --> UC1
    TargetUser --> UC2
    TargetUser --> UC4

    UC1 -. "include" .-> UC3
    UC3 -. "include" .-> UC4
    
    UC4 --> ExternalSystem

    %% Styles
    style TriggerUser fill:#f9f9f9,stroke:#333,stroke-width:2px
    style TargetUser fill:#f9f9f9,stroke:#333,stroke-width:2px
    style ExternalSystem fill:#f5f5f5,stroke:#333,stroke-width:2px
    style UC1 fill:#fff,stroke:#333,stroke-width:1px
    style UC2 fill:#fff,stroke:#333,stroke-width:1px
    style UC3 fill:#fff,stroke:#333,stroke-width:1px
    style UC4 fill:#fff,stroke:#333,stroke-width:1px
```

## Sequence

```mermaid
sequenceDiagram
    autonumber
    actor TriggerUser as 발생자 (Trigger User)
    actor TargetUser as 대상 사용자 (Target User)
    participant Server as 알림 시스템 (Server)

    %% 1. 알림 채널 구독 설정 (Pre-requisite)
    Note over TargetUser, Server: 대상 사용자는 웹 브라우저 진입 시 SSE 채널을 미리 구독함
    TargetUser->>Server: HTTP GET /api/v1/notifications/stream (SSE 연결 요청)
    activate Server
    Server-->>TargetUser: HTTP 200 OK (text/event-stream 연결 확립)

    %% 2. 인터랙션 발생 및 실시간 푸시
    TriggerUser->>Server: HTTP POST /api/v1/posts/123/likes (좋아요 클릭)
    Server-->>TriggerUser: HTTP 200 OK (요청 처리 완료)
    
    Note over Server: 내부 알림 이벤트 생성 및 라우팅<br>(NotificationEvent 생성)
    
    Server-->>TargetUser: SSE Event 푸시 (type: "LIKE", data: "OO님이 게시글을 좋아합니다")
    TargetUser->>TargetUser: 브라우저 UI 토스트 알림 표시 & 알림 배지 카운트 증가
    deactivate Server
```

## Domain Model

```mermaid
classDiagram
    class NotificationChannel {
        - String channelId
        - String targetUserId
        - ChannelStatus status
        - LocalDateTime connectedAt
        - List~BlackList~ mutingUsers
        + connect() void
        + disconnect() void
        + isDeliverable(NotificationEvent event) boolean
        + muteUser(String userId) void
    }

    class NotificationEvent {
        - String eventId
        - String triggerUserId
        - NotificationType type
        - EventPayload payload
        - LocalDateTime occurredAt
        + createPayloadSummary() String
    }

    class EventPayload {
        - String message
        - String targetUrl
        - boolean isRead
        - LocalDateTime readAt
        + markAsRead() void
    }

    class NotificationType {
        <<enumeration>>
        LIKE
        COMMENT
        FOLLOW
    }

    class ChannelStatus {
        <<enumeration>>
        CONNECTED
        DISCONNECTED
    }

    NotificationChannel "1" --> "1" ChannelStatus : tracks
    NotificationChannel ..> NotificationEvent : filters
    NotificationEvent "1" *-- "1" EventPayload : contains
    NotificationEvent "1" --> "1" NotificationType : categorizes
```

## State Transition

```mermaid
stateDiagram-v2
    [*] --> DISCONNECTED

    DISCONNECTED --> CONNECTED : connect() [브라우저 접속 및 SSE 구독 성공]
    
    state CONNECTED {
        [*] --> Active
        Active --> Active : isDeliverable() [알림 이벤트 수신 및 필터링/푸시]
    }

    CONNECTED --> DISCONNECTED : disconnect() [브라우저 종료 / 탭 닫기 / 네트워크 단절]
    DISCONNECTED --> [*]
```

## Policy

**요약 목록**

* **NotificationChannel 규칙**: SSE 연결 유효성 검증, 상태 기반의 이벤트 수신 가용성 판단, 블랙리스트 기반의 수신 거부(Filtering) 필터링이 핵심임.
* **NotificationEvent 규칙**: 필수 메타데이터(발생자, 대상자, 타입) 검증 및 도메인 행위에 따른 메시지 정책 템플릿 검증이 요구됨.
* **EventPayload 규칙**: 타겟 URL 포맷 검증 및 읽음 처리(`markAsRead`) 시 상태 전이 원자성 확보가 필요함.

---

### 1. NotificationChannel (애그리게잇 루트) 규칙 및 유효성 검사

* **연결 및 해제 상태 검증**
* `connect()` 수행 시, 이미 상태가 `CONNECTED`라면 기존 연결을 강제 종료(Disconnect)하거나 예외를 발생시켜 **사용자당 하나의 활성화된 라우팅 채널**의 정합성을 보장해야 합니다.
* `disconnect()`는 상태가 `CONNECTED`일 때만 유효하며, 해제 시 `connectedAt` 타임스탬프를 정리하거나 비활성화 상태로 안전하게 전이해야 합니다.


* **이벤트 배달 가용성 검증 (`isDeliverable`)**
* 채널 상태가 `DISCONNECTED`인 경우 알림 푸시를 원천 차단하고 오프라인 보관소(Database)로 이벤트를 우회시키는 규칙이 필요합니다.
* `mutingUsers`(특정 사용자 알림 차단 목록)를 조회하여, 수신된 `NotificationEvent`의 `triggerUserId`가 차단된 사용자에 해당하면 `false`를 반환하는 필터링 유효성 검사를 수행해야 합니다.



### 2. NotificationEvent 규칙 및 유효성 검사

* **식별자 및 컨텍스트 무결성**
* `eventId`, `triggerUserId`, `type`은 객체 생성 시 누락될 수 없는 불변(Immutable) 값이어야 합니다.
* 자기 자신에게 알림을 보내는 행위(예: 내가 내 글에 좋아요를 누름)를 방지하기 위해 `triggerUserId`와 채널의 `targetUserId`가 동일한지 검증하여 불필요한 이벤트 발행을 차단하는 도메인 규칙을 적용할 수 있습니다.


* **페이로드 생성 규칙**
* `type`(LIKE, COMMENT, FOLLOW)에 따라 `EventPayload`의 기본 메시지 포맷이 올바르게 생성되었는지 구조적 유효성을 검사해야 합니다.



### 3. EventPayload 규칙 및 유효성 검사

* **이동 경로 유효성**
* 알림 클릭 시 이동할 `targetUrl`은 상대 경로 포맷(예: `/posts/123`)을 준수해야 하며, 시스템 외부의 악의적인 주소로 리다이렉트되지 않도록 화이트리스트 검증을 적용해야 합니다.


* **읽음 상태 전이 규칙 (`markAsRead`)**
* 이미 `isRead` 상태가 `true`인 경우, `readAt` 시간이 중복 갱신되지 않도록 가드 코드가 필요합니다.
* `isRead`가 `true`로 변경되는 순간 `readAt`은 현재 시점의 타임스탬프로 원자적으로 함께 설정되어야 합니다.