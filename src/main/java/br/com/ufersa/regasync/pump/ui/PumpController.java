package br.com.ufersa.regasync.pump.ui;

import br.com.ufersa.regasync.pump.service.PumpService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class PumpController {
    private final PumpService service;
    public PumpController(PumpService service) { this.service=service; }
    @GetMapping("/bomba/historico")
    public String history(@RequestParam(defaultValue="0") int page, Model model) {
        model.addAttribute("events",service.history(page));
        return "pump/history";
    }
}
