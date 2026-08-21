package com.exampleco.crm.repository.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import com.exampleco.crm.domain.ticket.S2User;

public interface S2UserRepository extends JpaRepository<S2User, Long> {

    // Resolves the logged-in Authentication's username (see AppUserDetailsService) to this
    // read-only s2user mapping's id, so ticket-mutation endpoints can stamp handler_id/
    // created_by_id-style columns without pulling in the auth-domain S2UserAccount.
    Optional<S2User> findByUsername(String username);
}
