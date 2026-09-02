package com.exampleco.crm.repository.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import com.exampleco.crm.domain.auth.AppUserChannel;
import com.exampleco.crm.domain.auth.AppUserChannelId;

public interface AppUserChannelRepository extends JpaRepository<AppUserChannel, AppUserChannelId> {

    // Backs ChannelAccessService#resolveAllowedChannels for a single member — the set a
    // non-admin session is scoped to when listing/creating/viewing tickets.
    @Query("select c.sourceSystem from AppUserChannel c where c.s2userId = :s2userId")
    Set<String> findSourceSystemsByS2userId(Long s2userId);

    // Batched lookup backing the Members admin page's list (AppUserAdminController#listUsers) —
    // one IN-clause query for every account's grants, not a per-row lookup, same "enrich()"
    // pattern TicketController's batched fetches already follow.
    List<AppUserChannel> findByS2userIdIn(Collection<Long> s2userIds);

    // Used by AppUserAdminController#updateChannels to replace a member's whole grant set in one
    // go (delete-then-reinsert) — this table isn't @Audited, so unlike
    // TicketController#persistLineItems there's no audit-noise reason to reconcile by key instead
    // of diffing against what was there before.
    void deleteByS2userId(Long s2userId);
}
