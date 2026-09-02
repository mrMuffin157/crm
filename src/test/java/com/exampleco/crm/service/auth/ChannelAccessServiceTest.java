package com.exampleco.crm.service.auth;

import com.exampleco.crm.domain.ticket.S2User;
import com.exampleco.crm.domain.ticket.TicketEnums;
import com.exampleco.crm.repository.auth.AppUserChannelRepository;
import com.exampleco.crm.repository.ticket.S2UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// covers ChannelAccessService's core rule — an admin or the legacy-app M2M principal
// (ROLE_SERVICE) always resolves to the full channel universe, while an ordinary member gets
// exactly what's been explicitly granted via app_user_channel, defaulting to NONE (not
// "everything") when no grant exists yet. Plain Mockito unit test, no Spring context or
// datasource, same shape as TicketControllerSourceSystemTest.
@ExtendWith(MockitoExtension.class)
class ChannelAccessServiceTest {

    @Mock
    private S2UserRepository s2UserRepository;
    @Mock
    private AppUserChannelRepository appUserChannelRepository;

    private ChannelAccessService service;

    private static final Authentication ADMIN_AUTH = UsernamePasswordAuthenticationToken.authenticated(
            "admin1", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    private static final Authentication SERVICE_AUTH = UsernamePasswordAuthenticationToken.authenticated(
            "legacy-app", null, List.of(new SimpleGrantedAuthority(LegacyServiceAuthenticationFilter.AUTHORITY)));

    private static final Authentication MEMBER_AUTH = UsernamePasswordAuthenticationToken.authenticated(
            "csagent", null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

    @BeforeEach
    void setUp() {
        service = new ChannelAccessService(s2UserRepository, appUserChannelRepository);
    }

    @Test
    void resolveAllowedChannels_forAdmin_returnsFullChannelUniverse() {
        Set<String> allowed = service.resolveAllowedChannels(ADMIN_AUTH);

        assertThat(allowed).isEqualTo(TicketEnums.SOURCE_SYSTEM_CHANNELS);
        verifyNoInteractions(s2UserRepository, appUserChannelRepository);
    }

    @Test
    void resolveAllowedChannels_forServicePrincipal_returnsFullChannelUniverse() {
        Set<String> allowed = service.resolveAllowedChannels(SERVICE_AUTH);

        assertThat(allowed).isEqualTo(TicketEnums.SOURCE_SYSTEM_CHANNELS);
        verifyNoInteractions(s2UserRepository, appUserChannelRepository);
    }

    @Test
    void resolveAllowedChannels_forMemberWithGrants_returnsExactlyThoseGrants() {
        S2User user = new S2User();
        user.setId(7L);
        user.setUsername("csagent");
        when(s2UserRepository.findByUsername("csagent")).thenReturn(Optional.of(user));
        when(appUserChannelRepository.findSourceSystemsByS2userId(7L)).thenReturn(Set.of("az-us", "az-ca"));

        Set<String> allowed = service.resolveAllowedChannels(MEMBER_AUTH);

        assertThat(allowed).containsExactlyInAnyOrder("az-us", "az-ca");
    }

    // The whole point of no implicit "sees everything" default for a member who simply
    // hasn't been granted anything yet, unlike app_user_role's own access-allowlist behavior.
    @Test
    void resolveAllowedChannels_forMemberWithNoGrants_returnsEmptySet() {
        S2User user = new S2User();
        user.setId(7L);
        user.setUsername("csagent");
        when(s2UserRepository.findByUsername("csagent")).thenReturn(Optional.of(user));
        when(appUserChannelRepository.findSourceSystemsByS2userId(7L)).thenReturn(Set.of());

        Set<String> allowed = service.resolveAllowedChannels(MEMBER_AUTH);

        assertThat(allowed).isEmpty();
    }

    @Test
    void resolveAllowedChannels_forUnknownS2user_returnsEmptySetWithoutQueryingChannels() {
        when(s2UserRepository.findByUsername("csagent")).thenReturn(Optional.empty());

        Set<String> allowed = service.resolveAllowedChannels(MEMBER_AUTH);

        assertThat(allowed).isEmpty();
        verifyNoInteractions(appUserChannelRepository);
    }

    @Test
    void isChannelAuthorized_memberGrantedChannel_returnsTrueOnlyForThatChannel() {
        S2User user = new S2User();
        user.setId(7L);
        user.setUsername("csagent");
        lenient().when(s2UserRepository.findByUsername("csagent")).thenReturn(Optional.of(user));
        lenient().when(appUserChannelRepository.findSourceSystemsByS2userId(7L)).thenReturn(Set.of("az-us"));

        assertThat(service.isChannelAuthorized(MEMBER_AUTH, "az-us")).isTrue();
        assertThat(service.isChannelAuthorized(MEMBER_AUTH, "az-ca")).isFalse();
    }
}
