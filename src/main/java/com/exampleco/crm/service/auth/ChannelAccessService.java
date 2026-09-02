package com.exampleco.crm.service.auth;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import com.exampleco.crm.domain.auth.AppUserChannel;
import com.exampleco.crm.domain.auth.Role;
import com.exampleco.crm.domain.ticket.S2User;
import com.exampleco.crm.domain.ticket.TicketEnums;
import com.exampleco.crm.repository.auth.AppUserChannelRepository;
import com.exampleco.crm.repository.ticket.S2UserRepository;

// Resolves which ticket channels (sourceSystem codes) an authenticated caller may see, create, or
// act on (Ticket Data Authority). An admin (ROLE_ADMIN) or the legacy-app M2M principal
// (ROLE_SERVICE) gets the full TicketEnums.SOURCE_SYSTEM_CHANNELS universe — a service caller is
// already scoped to its own single channel by TicketController#enforceSourceSystemForService's
// required, exactly-matched sourceSystem param, so resolving "every channel" here for it is
// harmless, not an additional privilege. An ordinary member gets exactly what an admin has
// explicitly granted via app_user_channel — a member with no grants yet gets an EMPTY set, not
// "everything": unlike app_user_role's access-allowlist, there is no implicit default here, so an
// admin must actively grant at least one channel before a new member can see or create any
// ticket at all.
//
// Deliberately does not enrich the Authentication/UserDetails principal with this data (unlike,
// say, caching it on a custom UserDetails at login) — every other per-user lookup in this app
// (e.g. TicketController#createTicket resolving the handler via
// s2UserRepository.findByUsername(authentication.getName())) does a fresh repository read keyed
// off the username instead, and AppUserAdminController#updateRole's own comment already flags the
// staleness risk of anything cached at login time. A fresh read here means a just-granted or
// just-revoked channel takes effect on the caller's very next request, not just their next login.
@Service
public class ChannelAccessService {

    private final S2UserRepository s2UserRepository;
    private final AppUserChannelRepository appUserChannelRepository;

    public ChannelAccessService(S2UserRepository s2UserRepository, AppUserChannelRepository appUserChannelRepository) {
        this.s2UserRepository = s2UserRepository;
        this.appUserChannelRepository = appUserChannelRepository;
    }

    public Set<String> resolveAllowedChannels(Authentication authentication) {
        if (isAdmin(authentication) || isService(authentication)) {
            return TicketEnums.SOURCE_SYSTEM_CHANNELS;
        }
        Long s2userId = s2UserRepository.findByUsername(authentication.getName()).map(S2User::getId).orElse(null);
        if (s2userId == null) {
            return Set.of();
        }
        return appUserChannelRepository.findSourceSystemsByS2userId(s2userId);
    }

    public boolean isChannelAuthorized(Authentication authentication, String sourceSystem) {
        return resolveAllowedChannels(authentication).contains(sourceSystem);
    }

    // Replace-then-reinsert — this table isn't @Audited, so there's no audit-noise reason to
    // reconcile by key the way TicketController#persistLineItems now does.
    // deleteByS2userId is a derived delete query, which (unlike
    // save/findById) needs an actual transaction, not just the reactive shell's boundedElastic
    // thread — without @Transactional here it throws TransactionRequiredException, since
    // AppUserAdminController#updateChannels only wraps this in Mono.fromCallable, which defers
    // execution rather than opening one. Lives here, not on the controller, so the write and the
    // read paths above share one place that touches app_user_channel.
    @Transactional
    public void replaceChannels(Long s2userId, List<String> channels) {
        appUserChannelRepository.deleteByS2userId(s2userId);
        for (String channel : channels) {
            appUserChannelRepository.save(new AppUserChannel(s2userId, channel));
        }
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + Role.ADMIN).equals(a.getAuthority()));
    }

    private boolean isService(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> LegacyServiceAuthenticationFilter.AUTHORITY.equals(a.getAuthority()));
    }
}
