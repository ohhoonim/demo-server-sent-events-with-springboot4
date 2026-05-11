# demo-server-sent-events-with-springboot4
spring boot 4에서 SSE 구현

## 핵심 개념

- HTTP 기반: 기존 HTTP 연결을 유지하면서 서버가 데이터를 계속 전송.
- 단방향 통신: 서버 → 클라이언트 방향만 지원 (클라이언트 → 서버는 일반 요청 사용).
- 텍스트 이벤트 스트림: MIME 타입은 text/event-stream.
- 자동 재연결: 브라우저가 연결이 끊어지면 자동으로 다시 연결 시도.

## 사용 사례

- 실시간 알림(Notification)
- 주식 시세 업데이트
- 채팅 메시지 스트림 (읽기 전용)
- IoT 센서 데이터 모니터링


## 코드 예

```java

@RestController
public class SseMvcController {

    @GetMapping("/sse-mvc")
    public SseEmitter handleSse() {
        // 기본 타임아웃: 30초 (필요시 조정 가능)
        SseEmitter emitter = new SseEmitter(30_000L);

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                String data = "Current time: " + LocalTime.now();
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(System.currentTimeMillis()))
                        .name("time-event")
                        .data(data));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }, 0, 1, TimeUnit.SECONDS);

        return emitter;
    }
}
```
