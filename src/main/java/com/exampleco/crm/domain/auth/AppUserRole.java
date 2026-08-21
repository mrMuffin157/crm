package com.exampleco.crm.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// This app's own table (see schema.sql) — the only thing this app writes about who may use it.
// Login credentials themselves live in the real s2user table (a deliberate change from this
// app's earlier separate app_user table): every account that logs in must already be a real
// s2user row, and a row HERE grants that account access to this app plus a role. No row here
// (even for a valid s2user login) means no access — this table doubles as both the role
// assignment and the access allowlist, which is what keeps every real employee from suddenly
// being able to log into this internal tool just by existing in s2user.
@Entity
@Table(name = "app_user_role")
@Getter
@Setter
public class AppUserRole {

    // Same value as s2user.id — deliberately not a separate auto-generated id, since this is a
    // strict one-role-per-account mapping, not a list.
    @Id
    @Column(name = "s2user_id")
    private Long s2userId;

    // "ADMIN" or "MEMBER" — see Role.java.
    private String role;

    @Column(name = "date_created")
    private LocalDateTime dateCreated;
}
