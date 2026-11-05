package br.com.ufersa.regasync.auth.ui;

import br.com.ufersa.regasync.user.service.UserService;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
public class AuthController {
    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/login")
    public String login(Principal principal) {
        return principal == null ? "auth/login" : "redirect:/";
    }

    @GetMapping("/")
    public String home(Principal principal, Model model) {
        var account = userService.accountSummary(principal.getName());
        model.addAttribute("displayName", account.name());
        model.addAttribute("accountEmail", account.email());
        return "home";
    }

    @GetMapping("/acesso-negado")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String accessDenied() {
        return "error/403";
    }
}
