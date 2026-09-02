package com.exampleco.crm.controller.auth;

import com.exampleco.crm.controller.auth.AppUserAdminController.SetChannelsRequest;
import com.exampleco.crm.controller.auth.AppUserAdminController.UserAccessResult;
import com.exampleco.crm.domain.auth.AppUserRole;
import com.exampleco.crm.domain.auth.Role;
import com.exampleco.crm.domain.auth.S2UserAccount;
import com.exampleco.crm.repository.auth.AppUserChannelRepository;
import com.exampleco.crm.repository.auth.AppUserRoleRepository;
import com.exampleco.crm.repository.auth.S2UserAccountRepository;
import com.exampleco.crm.service.auth.ChannelAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// PATCH /api/admin/users/{id}/channels — the admin-facing side of channel-authorization
// grants. Plain Mockito unit test around the controller instance, same shape as
// TicketControllerSourceSystemTest — no Spring context, no datasource.
@ExtendWith(MockitoExtension.class)
class AppUserAdminControllerChannelTest {

    @Mock
    private AppUserRoleRepository appUserRoleRepository;
    @Mock
    private S2UserAccountRepository s2UserAccountRepository;
    @Mock
    private AppUserChannelRepository appUserChannelRepository;
    @Mock
    private ChannelAccessService channelAccessService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AppUserAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AppUserAdminController(appUserRoleRepository, s2UserAccountRepository,
                appUserChannelRepository, channelAccessService, passwordEncoder);
    }

    private AppUserRole memberRole(Long id) {
        AppUserRole role = new AppUserRole();
        role.setS2userId(id);
        role.setRole(Role.MEMBER);
        return role;
    }

    private S2UserAccount account(Long id, String username) {
        S2UserAccount account = new S2UserAccount();
        account.setId(id);
        account.setUsername(username);
        return account;
    }

    @Test
    void updateChannels_memberWithValidChannels_replacesGrantSetAndReturnsSortedDistinctChannels() {
        when(appUserRoleRepository.findById(7L)).thenReturn(Optional.of(memberRole(7L)));
        lenient().when(s2UserAccountRepository.findById(7L)).thenReturn(Optional.of(account(7L, "csagent")));

        Mono<ResponseEntity<Object>> result = controller.updateChannels(7L,
                new SetChannelsRequest(List.of("az-ca", "az-us", "az-us")));

        ResponseEntity<Object> response = result.block();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((UserAccessResult) response.getBody()).channels()).containsExactly("az-ca", "az-us");

        verify(channelAccessService).replaceChannels(7L, List.of("az-ca", "az-us"));
    }

    @Test
    void updateChannels_adminRow_isRejectedWithConflictAndNeverTouchesChannelRepository() {
        AppUserRole adminRole = new AppUserRole();
        adminRole.setS2userId(3L);
        adminRole.setRole(Role.ADMIN);
        when(appUserRoleRepository.findById(3L)).thenReturn(Optional.of(adminRole));

        Mono<ResponseEntity<Object>> result = controller.updateChannels(3L, new SetChannelsRequest(List.of("az-us")));

        ResponseEntity<Object> response = result.block();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(channelAccessService, never()).replaceChannels(any(), any());
    }

    @Test
    void updateChannels_unknownChannelCode_isRejectedWithBadRequest() {
        when(appUserRoleRepository.findById(7L)).thenReturn(Optional.of(memberRole(7L)));

        Mono<ResponseEntity<Object>> result = controller.updateChannels(7L, new SetChannelsRequest(List.of("not-a-real-channel")));

        ResponseEntity<Object> response = result.block();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(channelAccessService, never()).replaceChannels(any(), any());
    }

    @Test
    void updateChannels_unknownUserId_returnsNotFound() {
        when(appUserRoleRepository.findById(99L)).thenReturn(Optional.empty());

        Mono<ResponseEntity<Object>> result = controller.updateChannels(99L, new SetChannelsRequest(List.of("az-us")));

        assertThat(result.block().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
