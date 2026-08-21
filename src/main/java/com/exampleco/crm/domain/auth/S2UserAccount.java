package com.exampleco.crm.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// This app's authentication reads directly from the real s2user table (a deliberate decision —
// see AppUserRole's doc comment) — deliberately a SEPARATE mapping from
// com.exampleco.crm.ticket.S2User, which stays minimal/read-only for its existing
// handler/comment-author display use and must never map the password column. Used to
// authenticate real accounts, to create new ones (admin "create user", BootstrapAdminSeeder's
// bootstrap account), to reset an existing account's password (admin "reset password", the
// account owner's own self-service change), and to back ProfileController/AppUserAdminController's
// read/update responses — but only ever projected into a DTO that excludes password; the entity
// itself (and its password field) is never serialized directly.
@Entity
@Table(name = "s2user")
@Getter
@Setter
public class S2UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // GORM's optimistic-locking column — NOT NULL on the real table with no default, so every
    // insert needs it populated. @Version has Hibernate manage it the same way GORM does: 0 on
    // insert, incremented on each update.
    @Version
    private Long version;

    private String username;

    private String password;

    private String email;

    private String name;

    @Column(name = "account_expired")
    private Boolean accountExpired;

    @Column(name = "account_locked")
    private Boolean accountLocked;

    private Boolean enabled;

    @Column(name = "password_expired")
    private Boolean passwordExpired;

    // NOT NULL on the real table with no default — only ever set by BootstrapAdminSeeder when
    // creating this app's one bootstrap account; 0 is a "no Telegram integration" sentinel,
    // same as a real employee row that's never configured Telegram notifications would have.
    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    @Column(name = "date_created")
    private LocalDateTime dateCreated;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;
}
