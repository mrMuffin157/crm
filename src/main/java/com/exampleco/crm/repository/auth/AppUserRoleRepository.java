package com.exampleco.crm.repository.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import com.exampleco.crm.domain.auth.AppUserRole;
import com.exampleco.crm.dto.auth.UserAccessSummary;
import com.exampleco.crm.dto.auth.UserCandidate;

public interface AppUserRoleRepository extends JpaRepository<AppUserRole, Long> {

    long countByRole(String role);

    // Backs the Members admin page's account list — every account currently granted access to
    // this app, joined against s2user for its display fields. Never selects s2user.password.
    @Query(value = """
            SELECT u.id AS id, u.username AS username, u.email AS email, u.name AS name,
                   r.role AS role, r.date_created AS dateCreated
            FROM s2user u
            JOIN app_user_role r ON r.s2user_id = u.id
            ORDER BY u.username
            """, nativeQuery = true)
    List<UserAccessSummary> findAllWithRole();

    // Real employees who don't have a role row yet — the pool the "grant access" form picks
    // from, so granting access is "pick an existing real account" rather than "type a brand new
    // username into a table this app doesn't own."
    @Query(value = """
            SELECT u.id AS id, u.username AS username, u.email AS email, u.name AS name
            FROM s2user u
            LEFT JOIN app_user_role r ON r.s2user_id = u.id
            WHERE r.s2user_id IS NULL
            ORDER BY u.username
            """, nativeQuery = true)
    List<UserCandidate> findCandidates();
}
