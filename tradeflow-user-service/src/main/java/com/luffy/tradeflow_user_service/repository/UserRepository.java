package com.luffy.tradeflow_user_service.repository;

import com.luffy.tradeflow_user_service.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByMailId(String mailId);
}
