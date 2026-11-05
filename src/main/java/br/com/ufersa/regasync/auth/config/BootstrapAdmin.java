package br.com.ufersa.regasync.auth.config;

import br.com.ufersa.regasync.user.form.UserCreationForm;
import br.com.ufersa.regasync.user.model.Role;
import br.com.ufersa.regasync.user.model.UserAccount;
import br.com.ufersa.regasync.user.repository.UserAccountRepository;
import jakarta.validation.Validator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.bootstrap-admin.enabled", havingValue = "true")
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class BootstrapAdmin implements ApplicationRunner {
    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapAdminProperties properties;
    private final Validator validator;

    public BootstrapAdmin(UserAccountRepository repository, PasswordEncoder passwordEncoder,
                          BootstrapAdminProperties properties, Validator validator) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.validator = validator;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        UserCreationForm form = new UserCreationForm();
        form.setName(properties.name());
        form.setEmail(properties.email());
        form.setPassword(properties.password());
        form.setPasswordConfirmation(properties.password());
        if (!validator.validate(form).isEmpty()) {
            throw new IllegalStateException("Configure nome, e-mail válido e senha de 8 caracteres ou mais (até 72 bytes) para o administrador inicial.");
        }
        var existing = repository.findByEmail(form.getEmail());
        if (existing.isPresent()) {
            if (existing.get().getRole() != Role.ADMIN) {
                throw new IllegalStateException("O e-mail do administrador inicial já pertence a um usuário comum.");
            }
            return;
        }
        if (repository.existsByRole(Role.ADMIN)) {
            return;
        }
        repository.saveAndFlush(new UserAccount(form.getName(), form.getEmail(),
                passwordEncoder.encode(form.getPassword()), Role.ADMIN));
    }
}
