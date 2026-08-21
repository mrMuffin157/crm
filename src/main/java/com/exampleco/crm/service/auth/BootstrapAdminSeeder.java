package com.exampleco.crm.service.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import com.exampleco.crm.domain.auth.AppUserRole;
import com.exampleco.crm.domain.auth.Role;
import com.exampleco.crm.domain.auth.S2UserAccount;
import com.exampleco.crm.repository.auth.AppUserRoleRepository;
import com.exampleco.crm.repository.auth.S2UserAccountRepository;

// Creates the very first admin account so there's always a way in. Idempotent — a no-op once
// "admin" exists in s2user with a granted role — and re-creates just the role grant (never the
// s2user row itself) if that's ever removed, acting as this small deployment's designated
// break-glass login. This is the ONE place this app writes a brand new row to s2user — see
// S2UserAccount's own doc comment for why that's deliberately narrow.
@Component
public class BootstrapAdminSeeder implements CommandLineRunner {

    private static final String BOOTSTRAP_USERNAME = "admin";

    private final S2UserAccountRepository s2UserAccountRepository;
    private final AppUserRoleRepository appUserRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapPassword;

    // Read from BOOTSTRAP_ADMIN_PASSWORD with no default, same as DB_USER/DB_PASSWORD: a real
    // password should never live as a literal in version-controlled source.
    public BootstrapAdminSeeder(S2UserAccountRepository s2UserAccountRepository, AppUserRoleRepository appUserRoleRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${app.bootstrap-admin-password}") String bootstrapPassword) {
        this.s2UserAccountRepository = s2UserAccountRepository;
        this.appUserRoleRepository = appUserRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(String... args) {
        S2UserAccount account = s2UserAccountRepository.findByUsername(BOOTSTRAP_USERNAME).orElse(null);
        if (account == null) {
            account = new S2UserAccount();
            account.setUsername(BOOTSTRAP_USERNAME);
            account.setPassword(passwordEncoder.encode(bootstrapPassword));
            account.setAccountExpired(false);
            account.setAccountLocked(false);
            account.setEnabled(true);
            account.setPasswordExpired(false);
            account.setTelegramChatId(0L);
            account.setDateCreated(LocalDateTime.now());
            account.setLastUpdated(LocalDateTime.now());
            account = s2UserAccountRepository.save(account);
        }
        if (!appUserRoleRepository.existsById(account.getId())) {
            AppUserRole role = new AppUserRole();
            role.setS2userId(account.getId());
            role.setRole(Role.ADMIN);
            role.setDateCreated(LocalDateTime.now());
            appUserRoleRepository.save(role);
        }
    }
}
