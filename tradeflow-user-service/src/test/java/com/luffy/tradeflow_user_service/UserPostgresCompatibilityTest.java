package com.luffy.tradeflow_user_service;

import com.luffy.tradeflow.common.testsupport.PostgresTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.luffy.tradeflow_user_service.dto.RegisterRequest;
import com.luffy.tradeflow_user_service.repository.UserRepository;
import com.luffy.tradeflow_user_service.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway migrations, Hibernate validation and registration on real PostgreSQL 16. */
@Tag("postgres")
@SpringBootTest
@ActiveProfiles("test")
class UserPostgresCompatibilityTest {

    private static final String DATABASE = "tradeflow_user_it";

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase db = PostgresTestDatabase.get();
        registry.add("spring.datasource.url", () -> db.createDatabase(DATABASE));
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;

    @Test
    void registersUsersOnPostgres() {
        var user = userService.register(new RegisterRequest("Pg User", "Pg@Test.dev", "Secret#123", null, null,
                null, null, null, null, null));
        assertThat(userRepository.findByMailId("pg@test.dev")).get()
                .satisfies(u -> assertThat(u.getId()).isEqualTo(user.getId()));
    }
}
