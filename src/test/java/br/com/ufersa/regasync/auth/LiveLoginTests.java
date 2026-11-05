package br.com.ufersa.regasync.auth;

import br.com.ufersa.regasync.support.PostgresTestConfiguration;
import br.com.ufersa.regasync.user.model.Role;
import br.com.ufersa.regasync.user.repository.UserAccountRepository;
import jakarta.servlet.ServletContext;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.bootstrap-admin.enabled=true",
        "app.bootstrap-admin.name=Administrador de Teste",
        "app.bootstrap-admin.email=admin@regasync.test",
        "app.bootstrap-admin.password=SenhaTemporaria123!",
        "server.servlet.session.cookie.secure=false"
})
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class LiveLoginTests {
    @LocalServerPort int port;
    @Autowired UserAccountRepository repository;
    @Autowired ServletContext servletContext;

    @Test
    void realServerProvisionsAdminAndCompletesLoginCreationAndLogout() throws Exception {
        assertThat(repository.findByEmail("admin@regasync.test").orElseThrow().getRole()).isEqualTo(Role.ADMIN);
        assertThat(servletContext.getSessionTimeout()).isEqualTo(30);

        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            HttpResponse<String> loginPage = get(client, "/login");
            assertThat(loginPage.statusCode()).isEqualTo(200);
            assertThat(loginPage.headers().firstValue("set-cookie").orElseThrow())
                    .contains("HttpOnly", "SameSite=Lax").doesNotContain("; Secure");
            String originalSession = sessionId(cookies);

            HttpResponse<String> login = post(client, "/login", "email=admin%40regasync.test&password=SenhaTemporaria123%21&_csrf=" + csrf(loginPage.body()));
            assertThat(login.statusCode()).isEqualTo(302);
            assertThat(login.headers().firstValue("location").map(location -> url("/").resolve(location)))
                    .hasValue(url("/"));
            assertThat(sessionId(cookies)).isNotEqualTo(originalSession);

            HttpResponse<String> home = get(client, "/");
            assertThat(home.statusCode()).isEqualTo(200);
            assertThat(home.body()).contains("Administrador de Teste", "Criar usuário");

            HttpResponse<String> form = get(client, "/admin/usuarios/novo");
            assertThat(form.statusCode()).isEqualTo(200);
            String fields = "name=Pessoa+de+Teste&email=pessoa%40regasync.test&password=SenhaTemporaria123%21"
                    + "&passwordConfirmation=SenhaTemporaria123%21&role=ADMIN&_csrf=" + csrf(form.body());
            HttpResponse<String> creation = post(client, "/admin/usuarios", fields);
            assertThat(creation.statusCode()).isEqualTo(302);
            assertThat(creation.headers().firstValue("location").map(location -> url("/").resolve(location)))
                    .hasValue(url("/admin/usuarios/novo"));
            assertThat(repository.findByEmail("pessoa@regasync.test").orElseThrow().getRole()).isEqualTo(Role.USER);

            HttpResponse<String> logout = post(client, "/logout", "_csrf=" + csrf(home.body()));
            assertThat(logout.statusCode()).isEqualTo(302);
            assertThat(logout.headers().firstValue("location").map(location -> url("/").resolve(location)))
                    .hasValue(url("/login?logout"));
            assertThat(get(client, "/").statusCode()).isEqualTo(302);
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String csrf(String html) {
        var matcher = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).as("formulário contém token CSRF").isTrue();
        return URLEncoder.encode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private String sessionId(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .findFirst().orElseThrow().getValue();
    }
}
