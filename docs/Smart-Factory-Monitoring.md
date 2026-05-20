# Smart Factory Monitoring
Smart Factory Monitoring: 공장 센서의 메트릭 변화나 임계치 초과 이벤트를 대시보드로 실시간 단방향 브로드캐스팅함.
## Usecase

```mermaid
graph TD
    %% Actors
    Sensor[공장 센서<br>Factory Sensor]
    Admin([관리자<br>Factory Administrator])

    %% System Boundary
    subgraph SmartFactoryMonitoringSystem [스마트 팩토리 모니터링 시스템]
        UC1((실시간 센서 데이터 수집))
        UC2((관제 대시보드 SSE 구독))
        UC3((공정 및 가동 상태 모니터링))
        UC4((임계치 초과 알림 수신))
    end

    %% Relations
    Sensor --> UC1
    Admin --> UC2
    
    UC1 -. "include" .-> UC3
    UC1 -. "include" .-> UC4
    
    UC2 -. "include" .-> UC3
    UC2 -. "include" .-> UC4

    %% Styles
    style Sensor fill:#f5f5f5,stroke:#333,stroke-width:2px
    style Admin fill:#f9f9f9,stroke:#333,stroke-width:2px
    style UC1 fill:#fff,stroke:#333,stroke-width:1px
    style UC2 fill:#fff,stroke:#333,stroke-width:1px
    style UC3 fill:#fff,stroke:#333,stroke-width:1px
    style UC4 fill:#fff,stroke:#333,stroke-width:1px
```

## Sequence

```mermaid
sequenceDiagram
    autonumber
    actor Sensor as 공장 센서 (Sensor)
    participant Server as 모니터링 시스템 (Server)
    participant Dashboard as 관제 대시보드 (Admin Dashboard)

    %% 1. 대시보드 관제 시작 (Pre-requisite)
    Dashboard->>Server: HTTP GET /api/v1/factories/{id}/metrics (SSE 연결 요청)
    activate Server
    Server-->>Dashboard: HTTP 200 OK (text/event-stream 연결 확립)

    %% 2. 정상 메트릭 수집 및 브로드캐스트
    loop 주기적 데이터 수집
        Sensor->>Server: IoT 프로토콜 (MQTT/HTTP) 데이터 전송 (온도, 진동 등)
        Note over Server: 데이터 가공 및 임계치 검증 (정상)
        Server-->>Dashboard: SSE Event 푸시 (type: "factory-metric")
        Dashboard->>Dashboard: 실시간 시계열 차트 갱신 및 진행률 업데이트
    end

    %% 3. 이상 징후 발생 (임계치 초과)
    Sensor->>Server: 이상 데이터 전송 (온도 120°C 초과 위험 위험)
    Note over Server: 검증 엔진: 임계치 초과 판정 (Anomaly)
    
    Server-->>Dashboard: SSE Event 푸시 (type: "equipment-alarm", data: "위험 경고")
    Dashboard->>Dashboard: 대시보드 경고 팝업 및 해당 장비 UI 붉은색 점멸
    deactivate Server
```

## Domain Model

```mermaid
classDiagram
    class Equipment {
        - String equipmentId
        - String factoryId
        - EquipmentStatus status
        - double currentProgressRate
        - MetricThreshold threshold
        - LocalDateTime lastUpdatedAt
        + evaluateMetric(MetricReading reading) EquipmentEvent
        + updateProgress(double additionalProgress) void
        - transitStatus(EquipmentStatus newStatus) void
    }

    class MetricThreshold {
        - double maxTemperature
        - double maxVibration
        + isTemperatureExceeded(double value) boolean
        + isVibrationExceeded(double value) boolean
    }

    class MetricReading {
        - double temperature
        - double vibration
        - LocalDateTime readAt
    }

    class EquipmentEvent {
        - String eventId
        - String equipmentId
        - String eventType
        - String alarmMessage
        - LocalDateTime occurredAt
        + isCriticalAlarm() boolean
    }

    class EquipmentStatus {
        <<enumeration>>
        RUNNING
        IDLE
        ALARM
        STOPPED
    }

    Equipment "1" *-- "1" MetricThreshold : governs
    Equipment "1" --> "1" EquipmentStatus : tracks
    Equipment ..> MetricReading : evaluates
    Equipment ..> EquipmentEvent : outcomes
```

## State Transition

```mermaid
stateDiagram-v2
    [*] --> IDLE : 장비 초기 가동 준비

    IDLE --> RUNNING : startEquipment() [공정 개시 및 센서 작동]
    IDLE --> STOPPED : shutdown() [작업 종료 / 전원 오프]

    state RUNNING {
        [*] --> NormalOperation
        NormalOperation --> NormalOperation : evaluateMetric() [정상 범위 내 메트릭 누적]
    }

    RUNNING --> ALARM : evaluateMetric() [임계치 초과 판정 / Anomaly 발생]
    RUNNING --> IDLE : pauseEquipment() [배치 작업 완료 및 대기]
    RUNNING --> STOPPED : emergencyStop() [현장 긴급 정지 버튼 작동]

    state ALARM {
        [*] --> Tripped
        Tripped --> Tripped : streamAlarmLogs() [지속적인 위험 데이터 피드백]
    }

    ALARM --> IDLE : resolveAlarm() [오류 조치 및 초기화]
    ALARM --> STOPPED : forceStop() [원격 제어 및 안전 차단]

    STOPPED --> IDLE : maintenanceComplete() [정비 완료 및 해제]
    STOPPED --> [*]
```

## Policy

**요약 목록**

* **Equipment 데이터 및 상태 관리 규칙**: 진행률(`currentProgressRate`) 범위 검증 및 상태 다이어그램 규격에 맞는 안전한 내부 상태 전이 제어가 핵심임.
* **MetricThreshold & MetricReading 유효성 검사**: 센서 데이터의 물리적 한계점 검증(음수 값 차단) 및 임계치 비교 로직의 정확성 보장이 필요함.
* **EquipmentEvent 생성 규칙**: 이상 징후 발생 시 상태 전이와 연동된 원자적 이벤트 발행 및 알림 데이터 무결성 유지가 요구됨.

---

### 1. Equipment (애그리게잇 루트) 규칙 및 유효성 검사

* **공정 진행률 제약 조건 (`updateProgress`)**
* `currentProgressRate`는 항상 0% 이상 100% 이하의 범위를 유지해야 합니다. 공정 추가 진행률(`additionalProgress`)을 더했을 때 100%를 초과하려고 하면 100%로 상한선을 고정하거나, 공정 완료 이벤트(상태 전이)를 유도하는 도메인 로직이 작동해야 합니다.
* 장비가 가동 중이 아닌 상태(`IDLE`, `ALARM`, `STOPPED`)에서 진행률을 업데이트하려는 시도는 도메인 예외를 발생시켜야 합니다.


* **상태 기반 행위 가드 규칙 (`transitStatus`)**
* `evaluateMetric()` 호출 시, 현재 장비의 상태가 `STOPPED`라면 메트릭 평가를 수행하지 않고 즉시 반환하거나 예외를 처리해야 합니다.
* 내부 상태 전이 메서드인 `transitStatus()`는 제공된 **State Transition 다이어그램의 규칙을 강제**해야 합니다. 예를 들어, `RUNNING` 상태에서 관리자의 사전 조치(`resolveAlarm()`) 없이 즉시 `IDLE`로 돌아가는 규칙 위반 전이를 원천 차단해야 합니다.



### 2. MetricThreshold 및 MetricReading (밸류 오브젝트) 유효성 검사

* **물리적 수치 타당성 검증**
* 센서로부터 측정된 데이터(`MetricReading`)의 온도(캘빈/섭씨 기준 물리적 한계) 및 진동 값은 음수가 될 수 없으며, 시스템이 허용하는 최대 노이즈 범위를 초과하는 수치는 유효하지 않은 데이터로 간주하여 유입을 차단해야 합니다.
* `MetricThreshold`에 설정되는 `maxTemperature` 및 `maxVibration` 임계치 역시 0보다 커야 합니다.


* **임계치 평가 일관성**
* `isTemperatureExceeded()`와 `isVibrationExceeded()`는 단일 메트릭 값이 임계치와 '같거나 클 때' 초과로 판정할 것인지에 대한 경계 조건 규칙이 명확히 명시되어야 합니다.



### 3. EquipmentEvent (도메인 이벤트) 생성 및 발행 규칙

* **이벤트 생성 원자성**
* `evaluateMetric()`을 통해 임계치 초과가 감지되는 순간, `Equipment` 객체의 상태는 즉시 `ALARM`으로 변경되어야 하며, 변경과 동시에 내부 상태(상태 코드, 메시지 등)가 완전히 일치하는 `EquipmentEvent`가 원자적으로 생성되어야 합니다.


* **알림 메시지 정책**
* `alarmMessage`는 어떤 센서(온도 혹은 진동)가 임계치를 얼마나 초과했는지 명확히 식별할 수 있는 포맷으로 정밀하게 작성되어야 하며, 빈 값이나 식별 불가능한 일반 문자열은 허용되지 않습니다.