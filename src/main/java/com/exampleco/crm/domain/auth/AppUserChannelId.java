package com.exampleco.crm.domain.auth;

import java.io.Serializable;
import java.util.Objects;

// Composite primary key for AppUserChannel (s2user_id, source_system) — see that class's own
// comment. Field names must match AppUserChannel's @Id fields exactly (JPA's @IdClass contract).
public class AppUserChannelId implements Serializable {

    private Long s2userId;
    private String sourceSystem;

    public AppUserChannelId() {
    }

    public AppUserChannelId(Long s2userId, String sourceSystem) {
        this.s2userId = s2userId;
        this.sourceSystem = sourceSystem;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppUserChannelId that)) return false;
        return Objects.equals(s2userId, that.s2userId) && Objects.equals(sourceSystem, that.sourceSystem);
    }

    @Override
    public int hashCode() {
        return Objects.hash(s2userId, sourceSystem);
    }
}
