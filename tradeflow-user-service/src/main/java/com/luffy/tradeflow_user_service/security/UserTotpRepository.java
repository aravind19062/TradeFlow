package com.luffy.tradeflow_user_service.security;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTotpRepository extends JpaRepository<UserTotp, Long> {
}
