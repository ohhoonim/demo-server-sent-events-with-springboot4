package dev.ohhoonim.user;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import dev.ohhoonim.user.application.UserService;
import dev.ohhoonim.user.endpoint.UserRouter;
import dev.ohhoonim.user.model.User;

@WebMvcTest(UserRouter.class)
@AutoConfigureRestTestClient
class UserEndpointTest {

    @Autowired
    RestTestClient restTestClient;

    @MockitoBean
    UserService userService;


    @Test
    void getAllUsers_ShouldReturnUserList() {
        BDDMockito.given(userService.findAll()).willReturn(List.of(new User("UserA", "usera's name"), 
               new User( "UserB", "Userb's name")));

        var result = restTestClient.get().uri("/api/users").accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk().returnResult(String.class);

        String responseBody = result.getResponseBody();

        List<SseMessage> events = parseSse(responseBody);

        org.assertj.core.api.Assertions.assertThat(events).hasSize(2);

        SseMessage firstEvent = events.get(0);
        assertThat(firstEvent.id()).isEqualTo("1");
        assertThat(firstEvent.event()).isEqualTo("usersevent");
        assertThat(firstEvent.data())
                .isEqualTo("{\"id\":\"UserA\",\"name\":\"usera's name\"}");
    }

    private List<SseMessage> parseSse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return List.of();
        }

        return java.util.Arrays.stream(rawBody.split("\n\n")).filter(block -> !block.isBlank())
                .map(block -> {
                    String id = null;
                    String event = null;
                    String data = null;

                    for (String line : block.split("\n")) {
                        if (line.startsWith("id:")) {
                            id = line.substring(3).trim();
                        } else if (line.startsWith("event:")) {
                            event = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            data = line.substring(5).trim();
                        }
                    }
                    return new SseMessage(id, event, data);
                }).toList();
    }

    private record SseMessage(String id, String event, String data) {
    }

    @Test
    void createUser_ShouldReturnCreatedUser() {
        BDDMockito.given(userService.save()).willReturn("CreatedUser");

        restTestClient.post().uri("/api/users").exchange().expectStatus().isCreated()
                .expectBody(String.class).isEqualTo("CreatedUser");
    }

    @Test
    void getUserById_ShouldReturnUserDetail() {
        BDDMockito.given(userService.findById(1)).willReturn("User1");

        restTestClient.get().uri("/api/users/1").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("User1");
    }

    @Test
    void deleteUser_ShouldReturnNoContent() {
        BDDMockito.willDoNothing().given(userService).delete(1);

        restTestClient.delete().uri("/api/users/1").exchange().expectStatus().isNoContent();
    }
}
