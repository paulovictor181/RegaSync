package br.com.ufersa.regasync.user.repository;

import br.com.ufersa.regasync.user.model.Role;
import br.com.ufersa.regasync.user.model.UserAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(Role role);
}
