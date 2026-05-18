package dev.ohhoonim.user.application;

import java.util.List;
import org.springframework.stereotype.Service;
import dev.ohhoonim.user.model.User;

@Service
public class UserService {
    public List<User> findAll() {
        List<User> users = List.of(
            new User("user1", "user1's name"),
            new User("user2", "user2's name"),
            new User("user3", "user3's name"),
            new User("user4", "user4's name"),
            new User("user5", "user5's name"),
            new User("user6", "user6's name")
        );

        return users;
    }

    public String save() {
        return "CreatedUser";
    }

    public String findById(int id) {
        return "User" + id;
    }

    public void delete(int id) {
    }
}
