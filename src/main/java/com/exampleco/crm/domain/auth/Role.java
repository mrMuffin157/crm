package com.exampleco.crm.domain.auth;

// Just the two roles this app has. A constants holder rather than a Java enum so the raw
// string ("ADMIN"/"MEMBER") stored in AppUser.role and used to build Spring Security
// authorities ("ROLE_" + role) stays a single literal, not duplicated across files.
public final class Role {
    public static final String ADMIN = "ADMIN";
    public static final String MEMBER = "MEMBER";

    private Role() {
    }
}
