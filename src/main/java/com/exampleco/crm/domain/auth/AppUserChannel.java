package com.exampleco.crm.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// This app's own table (see schema.sql) — which ticket channels (source_system codes; see
// TicketEnums.SOURCE_SYSTEM_CHANNELS) a MEMBER may see or create tickets for.
// Composite-keyed on (s2user_id, source_system) rather than a single-row mapping like
// AppUserRole, since a member can be granted more than one channel. An admin's access is never
// stored here: ChannelAccessService treats ROLE_ADMIN as implicitly authorized for every
// channel, so rows here are only ever written/read for MEMBER accounts. No rows for a given
// s2user_id means that member currently has NO channel access at all — unlike AppUserRole's
// access-allowlist, there is no implicit "sees everything" default here.
@Entity
@Table(name = "app_user_channel")
@IdClass(AppUserChannelId.class)
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class AppUserChannel {

    @Id
    @Column(name = "s2user_id")
    private Long s2userId;

    @Id
    @Column(name = "source_system")
    private String sourceSystem;
}
