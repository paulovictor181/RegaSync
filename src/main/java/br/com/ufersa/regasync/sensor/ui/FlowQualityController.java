package br.com.ufersa.regasync.sensor.ui;

import br.com.ufersa.regasync.sensor.config.FlowQualityPolicy;
import br.com.ufersa.regasync.sensor.form.FlowQualityForm;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class FlowQualityController {
    private final FlowQualityPolicy quality;
    public FlowQualityController(FlowQualityPolicy quality) { this.quality=quality; }
    @InitBinder("qualityForm") void bind(WebDataBinder binder) { binder.setAllowedFields("minimum","maximum"); }
    @GetMapping("/admin/configuracoes/fluxo")
    public String form(Model model) {
        var limits=quality.limits();var form=new FlowQualityForm();form.setMinimum(limits.minimum());form.setMaximum(limits.maximum());
        model.addAttribute("qualityForm",form);return "sensors/quality-form";
    }
    @PostMapping("/admin/configuracoes/fluxo")
    public String save(@Valid @ModelAttribute("qualityForm") FlowQualityForm form,BindingResult errors,RedirectAttributes flash) {
        if(errors.hasErrors()) return "sensors/quality-form";
        quality.save(form);flash.addFlashAttribute("successMessage","Limites de vazão salvos. Os totais e gráficos já usam a nova faixa.");
        return "redirect:/admin/configuracoes/fluxo";
    }
}
