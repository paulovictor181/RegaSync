package br.com.ufersa.regasync.sensor.ui;

import br.com.ufersa.regasync.sensor.form.SensorForm;
import br.com.ufersa.regasync.sensor.model.SensorType;
import br.com.ufersa.regasync.sensor.service.SensorService;
import jakarta.validation.Valid;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SensorController {
    private final SensorService service;
    public SensorController(SensorService service){this.service=service;}
    @GetMapping("/sensores") public String list(@RequestParam(required=false) String q,@RequestParam(required=false) SensorType type,
            @RequestParam(required=false) Boolean active,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,
            @RequestParam(defaultValue="name") String sort,@RequestParam(defaultValue="asc") String direction,Model model){
        model.addAttribute("sensors",service.search(q,type,active,page,size,sort,direction));model.addAttribute("q",q);model.addAttribute("type",type);model.addAttribute("active",active);model.addAttribute("size",size);model.addAttribute("sort",sort);model.addAttribute("direction",direction);model.addAttribute("types",SensorType.values());return "sensors/list";
    }
    @GetMapping("/sensores/{id}") public String detail(@PathVariable long id,@RequestParam(defaultValue="0") int page,Model model){
        var sensor=service.get(id);Instant now=Instant.now();model.addAttribute("sensor",sensor);model.addAttribute("latest",service.latest(id).orElse(null));model.addAttribute("readings",service.history(id,now.minus(Duration.ofDays(30)),now.plus(Duration.ofMinutes(5)),PageRequest.of(Math.max(0,page),20,org.springframework.data.domain.Sort.by("measuredAt").descending())));return "sensors/detail";
    }
    @GetMapping("/admin/sensores/novo") public String newSensor(Model model){model.addAttribute("sensorForm",new SensorForm());model.addAttribute("types",SensorType.values());return "sensors/form";}
    @GetMapping("/admin/sensores/{id}/editar") public String edit(@PathVariable long id,Model model){var s=service.get(id);var f=new SensorForm();f.populate(s);model.addAttribute("sensor",s);model.addAttribute("sensorForm",f);model.addAttribute("types",SensorType.values());return "sensors/form";}
    @InitBinder("sensorForm") void bind(WebDataBinder b){b.setAllowedFields("name","type","number","dryAdc","wetAdc","pulsesPerLiter","active");}
    @PostMapping("/admin/sensores") public String create(@Valid @ModelAttribute("sensorForm") SensorForm f,BindingResult errors,Model model,RedirectAttributes flash){
        if(errors.hasErrors()){model.addAttribute("types",SensorType.values());return "sensors/form";}
        try{var s=service.create(f);flash.addFlashAttribute("successMessage","Sensor cadastrado: "+s.topic());return "redirect:/sensores/"+s.getId();}
        catch(IllegalArgumentException e){errors.reject("sensor.invalid",e.getMessage());model.addAttribute("types",SensorType.values());return "sensors/form";}
    }
    @PostMapping("/admin/sensores/{id}/editar") public String update(@PathVariable long id,@Valid @ModelAttribute("sensorForm") SensorForm f,BindingResult errors,Model model,RedirectAttributes flash){
        if(errors.hasErrors()){model.addAttribute("sensor",service.get(id));model.addAttribute("types",SensorType.values());return "sensors/form";}
        try{service.update(id,f);flash.addFlashAttribute("successMessage","Configuração do sensor atualizada.");return "redirect:/sensores/"+id;}
        catch(IllegalArgumentException e){errors.reject("sensor.invalid",e.getMessage());model.addAttribute("sensor",service.get(id));model.addAttribute("types",SensorType.values());return "sensors/form";}
    }
    @GetMapping(value="/api/sensores/atuais",produces=MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody public Map<Long,Object> latest(@RequestParam List<Long> ids){
        if(ids.size()>50)throw new IllegalArgumentException("Até 50 sensores por consulta.");Map<Long,Object> out=new LinkedHashMap<>();
        service.latestFor(ids).forEach((id,r)->out.put(id,Map.of("valor",r.getConvertedValue(),"unidade",r.getUnit(),"medidoEm",r.getMeasuredAt().toString(),"recebidoEm",r.getReceivedAt().toString())));return out;
    }
}
