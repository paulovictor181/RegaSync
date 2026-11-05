package br.com.ufersa.regasync.auth;

import br.com.ufersa.regasync.support.IntegrationTestSupport;
import br.com.ufersa.regasync.user.model.Role;
import br.com.ufersa.regasync.user.model.UserAccount;
import br.com.ufersa.regasync.user.repository.UserAccountRepository;
import br.com.ufersa.regasync.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthFlowTests extends IntegrationTestSupport {
    private static final String PASSWORD = "SenhaSegura123!";
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository repository;
    @Autowired PasswordEncoder encoder;
    @Autowired UserService userService;

    @BeforeEach
    void seedAccounts() {
        repository.deleteAll();
        repository.saveAndFlush(new UserAccount("Administrador", "admin@example.com", encoder.encode(PASSWORD), Role.ADMIN));
        repository.saveAndFlush(new UserAccount("Maria Silva", "maria@example.com", encoder.encode(PASSWORD), Role.USER));
    }

    @Test
    void anonymousCanSeeLoginAndAssetsButNotProtectedPages() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("Criar usuário"))));
        mvc.perform(get("/favicon.svg")).andExpect(status().isOk());
        mvc.perform(get("/css/app.css")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/admin/usuarios/novo")).andExpect(status().is3xxRedirection());
        mvc.perform(createRequest()).andExpect(status().is3xxRedirection());
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void loginNormalizesEmailAndDoesNotRetainCredentialsInSession() throws Exception {
        var result = mvc.perform(login("  MARIA@EXAMPLE.COM  ", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername("maria@example.com"))
                .andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        Authentication authentication = ((SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT")).getAuthentication();
        assertThat(authentication.getCredentials()).isNull();
        assertThat(((org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal()).getPassword()).isNull();
        mvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Maria Silva")))
                .andExpect(content().string(not(containsString("Criar usuário"))))
                .andExpect(content().string(not(containsString(repository.findByEmail("maria@example.com").orElseThrow().getPasswordHash()))));
        mvc.perform(get("/login").session(session)).andExpect(redirectedUrl("/"));
    }

    @Test
    void invalidAndUnknownCredentialsHaveTheSamePublicResponse() throws Exception {
        mvc.perform(login("maria@example.com", "incorreta"))
                .andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        mvc.perform(login("ausente@example.com", PASSWORD))
                .andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        mvc.perform(get("/login?error"))
                .andExpect(content().string(containsString("E-mail ou senha inválidos")))
                .andExpect(content().string(not(containsString("ausente@example.com"))));
    }

    @Test
    void successfulLoginChangesSessionIdAndReturnsToSavedPage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String originalId = session.getId();
        mvc.perform(get("/admin/usuarios/novo").session(session)).andExpect(status().is3xxRedirection());
        var result = mvc.perform(login("admin@example.com", PASSWORD).session(session).with(request -> {
                    request.setRequestedSessionId(session.getId());
                    request.setRequestedSessionIdValid(true);
                    return request;
                }))
                .andExpect(authenticated().withRoles("ADMIN"))
                .andExpect(redirectedUrlPattern("http://localhost/admin/usuarios/novo*"))
                .andReturn();
        assertThat(result.getRequest().getSession(false).getId()).isNotEqualTo(originalId);
    }

    @Test
    void loginRequiresAValidCsrfToken() throws Exception {
        mvc.perform(post("/login").param("email", "maria@example.com").param("password", PASSWORD))
                .andExpect(status().isForbidden()).andExpect(unauthenticated());
        mvc.perform(login("maria@example.com", PASSWORD).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden()).andExpect(unauthenticated());
    }

    @Test
    void logoutInvalidatesSessionAndClearsCookie() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(login("maria@example.com", PASSWORD))
                .andReturn().getRequest().getSession(false);
        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"))
                .andExpect(cookie().maxAge("JSESSIONID", 0))
                .andExpect(unauthenticated());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/login?logout")).andExpect(content().string(containsString("Você saiu da sua conta com sucesso")));
    }

    @Test
    void logoutWithoutCsrfAndGetLogoutDoNotEndSession() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(login("maria@example.com", PASSWORD))
                .andReturn().getRequest().getSession(false);
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/logout").session(session)).andExpect(status().isNotFound());
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/").session(session)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "maria@example.com")
    void userCannotAccessAdminFormOrCreateAccounts() throws Exception {
        mvc.perform(get("/admin/usuarios/novo")).andExpect(status().isForbidden());
        mvc.perform(createRequest()).andExpect(status().isForbidden());
        mvc.perform(get("/acesso-negado")).andExpect(status().isForbidden()).andExpect(view().name("error/403"));
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @WithMockUser(username = "maria@example.com")
    void currentUserCannotReadAnotherUsersDetails() throws Exception {
        mvc.perform(get("/").param("email", "admin@example.com"))
                .andExpect(model().attribute("accountEmail", "maria@example.com"))
                .andExpect(content().string(not(containsString("admin@example.com"))));
    }

    @Test
    @WithMockUser(username = "maria@example.com")
    void methodSecurityAlsoProtectsAnotherUsersDetails() {
        assertThatThrownBy(() -> userService.accountSummary("admin@example.com"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void adminFormHasCsrfAndCreatesOnlyUserRole() throws Exception {
        mvc.perform(get("/admin/usuarios/novo"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(createRequest().param("role", "ADMIN").param("id", "1"))
                .andExpect(redirectedUrl("/admin/usuarios/novo"))
                .andExpect(flash().attributeExists("successMessage"));
        UserAccount created = repository.findByEmail("nova@example.com").orElseThrow();
        assertThat(created.getName()).isEqualTo("Nova Pessoa");
        assertThat(created.getRole()).isEqualTo(Role.USER);
        assertThat(created.getPasswordHash()).startsWith("{bcrypt}").isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, created.getPasswordHash())).isTrue();
        TestSecurityContextHolder.clearContext();
        mvc.perform(login("nova@example.com", PASSWORD))
                .andExpect(authenticated().withUsername("nova@example.com").withRoles("USER"));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void creationRequiresCsrf() throws Exception {
        mvc.perform(post("/admin/usuarios").param("name", "Pessoa"))
                .andExpect(status().isForbidden());
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void duplicateEmailIsRejectedIgnoringCaseAndWhitespace() throws Exception {
        mvc.perform(createRequest("  MARIA@EXAMPLE.COM  ", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("userForm", "email"))
                .andExpect(content().string(containsString("Já existe um usuário com este e-mail")))
                .andExpect(content().string(not(containsString(PASSWORD))));
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void invalidCreationIsRejectedAndPasswordsAreNeverRendered() throws Exception {
        mvc.perform(post("/admin/usuarios").with(csrf())
                        .param("name", " ").param("email", "invalido")
                        .param("password", "curta").param("passwordConfirmation", "diferente"))
                .andExpect(model().attributeHasFieldErrors("userForm", "name", "email", "password", "passwordConfirmed"))
                .andExpect(content().string(not(containsString("value=\"curta\""))))
                .andExpect(content().string(not(containsString("value=\"diferente\""))));
        String multibytePassword = "á".repeat(37);
        mvc.perform(createRequest("nova@example.com", multibytePassword))
                .andExpect(model().attributeHasFieldErrors("userForm", "passwordWithinLimit"));
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @WithMockUser(username = "maria@example.com")
    void methodSecurityAlsoProtectsCreation() {
        var form = new br.com.ufersa.regasync.user.form.UserCreationForm();
        form.setName("Nova Pessoa");
        form.setEmail("nova@example.com");
        form.setPassword(PASSWORD);
        form.setPasswordConfirmation(PASSWORD);
        assertThatThrownBy(() -> userService.createUser(form))
                .isInstanceOf(AccessDeniedException.class);
    }

    private MockHttpServletRequestBuilder login(String email, String password) {
        return post("/login").with(csrf()).param("email", email).param("password", password);
    }

    private MockHttpServletRequestBuilder createRequest() {
        return createRequest("  NOVA@EXAMPLE.COM  ", PASSWORD);
    }

    private MockHttpServletRequestBuilder createRequest(String email, String password) {
        return post("/admin/usuarios").with(csrf())
                .param("name", "  Nova Pessoa  ").param("email", email)
                .param("password", password).param("passwordConfirmation", password);
    }
}
