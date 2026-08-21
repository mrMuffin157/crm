package com.exampleco.crm.repository.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import com.exampleco.crm.domain.auth.S2UserAccount;

public interface S2UserAccountRepository extends JpaRepository<S2UserAccount, Long> {
    Optional<S2UserAccount> findByUsername(String username);
}
