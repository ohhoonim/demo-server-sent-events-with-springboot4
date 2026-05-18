package dev.ohhoonim.user.endpoint;

import static org.springframework.web.servlet.function.ServerResponse.ok;
import static org.springframework.web.servlet.function.ServerResponse.sse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import dev.ohhoonim.user.application.UserService;
import dev.ohhoonim.user.model.User;
import tools.jackson.databind.ObjectMapper;

@Component
public class UserRouter implements Supplier<RouterFunction<ServerResponse>> {

    private final UserHandlers handler;

    public UserRouter(UserHandlers handler) {
        this.handler = handler;
    }

    @Override
    @Bean
    public RouterFunction<ServerResponse> get() {
        return RouterFunctions.route().path("/api/users",
                builder -> builder.GET("", handler.getAllUsers).POST("", handler.createUser)
                        .GET("/{id}", handler.getUserById).DELETE("/{id}", handler.deleteUser))
                .build();
    }

    @Component
    public static class UserHandlers {
        public final HandlerFunction<ServerResponse> deleteUser;
        public final HandlerFunction<ServerResponse> getUserById;
        public final HandlerFunction<ServerResponse> createUser;
        public final HandlerFunction<ServerResponse> getAllUsers;

        // curl http://localhost:8080/api/users -H "accept: application/text-stream"
        public UserHandlers(UserService userService, ObjectMapper objectMapper) {
            this.getAllUsers = req -> {
                List<User> users = userService.findAll();
                AtomicInteger idx = new AtomicInteger(0);
                return sse(sse -> {
                    try {
                        // stream().forEach 대신 명시적인 for 루프를 사용하여 자원 제어력 확보
                        for (User u : users) {
                            // 스레드가 이미 인터럽트 되었거나 닫혔는지 사전 검증
                            if (Thread.currentThread().isInterrupted()) {
                                break;
                            }

                            sse.id(String.valueOf(idx.incrementAndGet())).event("usersevent")
                                    .send(objectMapper.writeValueAsString(u));

                            Thread.sleep(1000);
                        }
                        sse.event("complete").send("stream-finished");
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        sse.onError(
                                err -> new RuntimeException("Stream interrupted dynamically", ie));
                    } catch (Exception e) {
                        sse.onError(err -> new RuntimeException("Stream logic failure", e));
                    } finally {
                        // 반드시 스트림을 완결시키고 콜백을 비워 톰캣 리사이클 유효성 검증을 통과시킴
                        sse.onComplete(() -> IO.println("전송 완료"));
                        sse.complete();
                    }
                });
            };
            this.getUserById = req -> {
                int id = Integer.parseInt(req.pathVariable("id"));
                return ok().body(userService.findById(id));
            };
            this.createUser = req -> {
                var user = userService.save();
                return ServerResponse.status(201).body(user);
            };
            this.deleteUser = req -> {

                int id = Integer.parseInt(req.pathVariable("id"));
                userService.delete(id);
                return ServerResponse.noContent().build();
            };
        }
    }
}


