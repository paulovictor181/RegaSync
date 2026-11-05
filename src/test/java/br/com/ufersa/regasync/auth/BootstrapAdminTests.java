package br.com.ufersa.regasync.auth;

import br.com.ufersa.regasync.auth.config.BootstrapAdmin;
import br.com.ufersa.regasync.auth.config.BootstrapAdminProperties;
import br.com.ufersa.regasync.support.IntegrationTestSupport;
import br.com.ufersa.regasync.user.model.Role;
import br.com.ufersa.regasync.user.model.UserAccount;
import br.com.ufersa.regasync.user.repository.UserAccountRepository;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.*;

class BootstrapAdminTests extends IntegrationTestSupport {
    @Autowired UserAccountRepository repository;
    @Autowired PasswordEncoder encoder;
    @Autowired Validator validator;

    @BeforeEach
    void clearAccounts() {
        repository.deleteAll();
    }

    @Test
    void createsAdministratorOnlyOnceAndNeverOverwritesCredentials() {
        bootstrap("Primeiro Admin", "  ADMIN@EXAMPLE.COM  ", "PrimeiraSenha123!").run(null);
        UserAccount original = repository.findByEmail("admin@example.com").orElseThrow();
        assertThat(original.getRole()).isEqualTo(Role.ADMIN);
        assertThat(encoder.matches("PrimeiraSenha123!", original.getPasswordHash())).isTrue();
        bootstrap("Outro Nome", "admin@example.com", "SegundaSenha123!").run(null);
        UserAccount current = repository.findByEmail("admin@example.com").orElseThrow();
        assertThat(current.getName()).isEqualTo("Primeiro Admin");
        assertThat(current.getPasswordHash()).isEqualTo(original.getPasswordHash());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void cannotPromoteExistingUserThroughBootstrap() {
        repository.saveAndFlush(new UserAccount("Maria", "maria@example.com", encoder.encode("SenhaSegura123!"), Role.USER));
        assertThatThrownBy(() -> bootstrap("Admin", "maria@example.com", "SenhaSegura123!").run(null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findByEmail("maria@example.com").orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void doesNotCreateAnotherAdministratorIfOneAlreadyExists() {
        bootstrap("Admin", "admin@example.com", "SenhaSegura123!").run(null);
        bootstrap("Admin Dois", "outro@example.com", "SenhaSegura123!").run(null);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void invalidConfigurationFailsWithoutDisclosingSecrets() {
        assertThatThrownBy(() -> bootstrap("", "invalido", "segredo").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("segredo");
        assertThatThrownBy(() -> bootstrap("Admin", "admin@example.com", "á".repeat(37)).run(null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new BootstrapAdminProperties("Admin", "admin@example.com", "segredo").toString())
                .doesNotContain("segredo");
        assertThat(repository.count()).isZero();
    }

    private BootstrapAdmin bootstrap(String name, String email, String password) {
        return new BootstrapAdmin(repository, encoder, new BootstrapAdminProperties(name, email, password), validator);
    }
}
