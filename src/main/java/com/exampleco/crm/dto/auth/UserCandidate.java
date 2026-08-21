package com.exampleco.crm.dto.auth;


// Projection for AppUserRoleRepository#findCandidates — real s2user accounts that don't have an
// app_user_role grant yet, i.e. the pool the "grant access" form picks a username from, rather
// than free-typing a brand new one into the real employee table.
public interface UserCandidate {
    Long getId();

    String getUsername();

    String getEmail();

    String getName();
}
