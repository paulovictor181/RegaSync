package br.com.ufersa.regasync.user.service;

import br.com.ufersa.regasync.user.form.UserCreationForm;
import br.com.ufersa.regasync.user.model.Role;
import br.com.ufersa.regasync.user.model.AccountSummary;
import br.com.ufersa.regasync.user.model.UserAccount;
import br.com.ufersa.regasync.user.repository.UserAccountRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class UserService {
    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserAccountRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated() and #email == authentication.name")
    public AccountSummary accountSummary(String email) {
        UserAccount account = repository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Conta não encontrada."));
        return new AccountSummary(account.getName(), account.getEmail());
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void createUser(@Valid UserCreationForm form) {
        if (repository.existsByEmail(form.getEmail())) {
            throw new DuplicateEmailException();
        }
        try {
            repository.saveAndFlush(new UserAccount(form.getName(), form.getEmail(),
                    passwordEncoder.encode(form.getPassword()), Role.USER));
        } catch (DataIntegrityViolationException exception) {
            // The unique constraint also protects concurrent requests for the same email.
            if (exception.getMostSpecificCause().getMessage() != null
                    && exception.getMostSpecificCause().getMessage().contains("uk_user_accounts_email")) {
                throw new DuplicateEmailException();
            }
            throw exception;
        }
    }
}
