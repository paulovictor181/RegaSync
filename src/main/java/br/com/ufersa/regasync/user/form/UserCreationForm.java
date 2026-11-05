package br.com.ufersa.regasync.user.form;

import br.com.ufersa.regasync.user.model.UserAccount;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserCreationForm {
    @NotBlank(message = "Informe o nome.")
    @Size(max = 120, message = "O nome deve ter até 120 caracteres.")
    private String name;

    @NotBlank(message = "Informe o e-mail.")
    @Email(message = "Informe um e-mail válido.")
    @Size(max = 254, message = "O e-mail deve ter até 254 caracteres.")
    private String email;

    @NotBlank(message = "Informe a senha.")
    @Size(min = 8, max = 72, message = "A senha deve ter entre 8 e 72 caracteres.")
    private String password;

    @NotBlank(message = "Confirme a senha.")
    private String passwordConfirmation;

    public void setName(String name) {
        this.name = name == null ? null : name.strip();
    }

    public void setEmail(String email) {
        this.email = UserAccount.normalizeEmail(email);
    }

    @AssertTrue(message = "A senha e a confirmação devem ser iguais.")
    public boolean isPasswordConfirmed() {
        return Objects.equals(password, passwordConfirmation);
    }

    @AssertTrue(message = "A senha deve ocupar até 72 bytes; caracteres especiais podem ocupar mais de um byte.")
    public boolean isPasswordWithinLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
