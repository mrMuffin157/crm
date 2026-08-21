package com.exampleco.crm.dto.auth;

import java.time.LocalDateTime;

// Projection for AppUserRoleRepository#findAllWithRole — one row per account that currently has
// access to this app (an s2user row joined to its app_user_role grant). Deliberately excludes
// s2user.password.
public interface UserAccessSummary {
    Long getId();

    String getUsername();

    String getEmail();

    String getName();

    String getRole();

    LocalDateTime getDateCreated();
}
