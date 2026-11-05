package br.com.ufersa.regasync.user.ui;

import br.com.ufersa.regasync.user.form.UserCreationForm;
import br.com.ufersa.regasync.user.service.DuplicateEmailException;
import br.com.ufersa.regasync.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/usuarios")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @InitBinder("userForm")
    void bindUserForm(WebDataBinder binder) {
        binder.setAllowedFields("name", "email", "password", "passwordConfirmation");
    }

    @GetMapping("/novo")
    public String newUser(Model model) {
        model.addAttribute("userForm", new UserCreationForm());
        return "users/new";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("userForm") UserCreationForm form,
                         BindingResult errors, RedirectAttributes redirect) {
        if (errors.hasErrors()) {
            return "users/new";
        }
        try {
            userService.createUser(form);
        } catch (DuplicateEmailException exception) {
            errors.rejectValue("email", "duplicate", exception.getMessage());
            return "users/new";
        }
        redirect.addFlashAttribute("successMessage", "Usuário criado. Ele já pode acessar com o e-mail e a senha informados.");
        return "redirect:/admin/usuarios/novo";
    }
}
