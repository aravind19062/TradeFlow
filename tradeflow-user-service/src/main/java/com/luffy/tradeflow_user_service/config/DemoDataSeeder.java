package com.luffy.tradeflow_user_service.config;

import com.luffy.tradeflow_user_service.entity.User;
import com.luffy.tradeflow_user_service.repository.UserRepository;
import com.luffy.tradeflow_user_service.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DEMO DATA. Creates a demo trader and a demo admin on first start so the
 * walkthrough in the README works out of the box. Registration goes through
 * {@link UserService}, so portfolio accounts are opened by the normal event flow.
 * Disable with TRADEFLOW_DEMO_ENABLED=false.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tradeflow.demo.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_EMAIL = "demo@tradeflow.dev";
    public static final String ADMIN_EMAIL = "admin@tradeflow.dev";

    private final UserService userService;
    private final UserRepository userRepository;
    private final String demoPassword;

    public DemoDataSeeder(UserService userService, UserRepository userRepository,
                          @Value("${tradeflow.demo.password}") String demoPassword) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(DEMO_EMAIL, "Demo Trader", User.ROLE_USER);
        seed(ADMIN_EMAIL, "Demo Admin", User.ROLE_ADMIN);
    }

    private void seed(String email, String name, String role) {
        if (userRepository.findByMailId(email).isPresent()) {
            return;
        }
        User user = new User();
        user.setName(name);
        user.setMailId(email);
        user.setPassword(demoPassword);
        user.setRole(role);
        userService.registerUser(user);
        log.info("Seeded demo user {} ({})", email, role);
    }
}
