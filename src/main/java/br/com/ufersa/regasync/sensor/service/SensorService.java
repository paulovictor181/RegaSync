package br.com.ufersa.regasync.sensor.service;

import br.com.ufersa.regasync.sensor.form.SensorForm;
import br.com.ufersa.regasync.sensor.model.*;
import br.com.ufersa.regasync.sensor.repository.*;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SensorService {
    private final SensorRepository sensors;
    private final SensorReadingRepository readings;
    private final ReadingMessage decoder;
    public SensorService(SensorRepository sensors, SensorReadingRepository readings, ReadingMessage decoder) { this.sensors=sensors; this.readings=readings; this.decoder=decoder; }
    @Transactional(readOnly=true)
    public Page<Sensor> search(String q, SensorType type, Boolean active, int page, int size, String sort, String direction) {
        String field=List.of("name","type","number","active","updatedAt").contains(sort)?sort:"name";
        Specification<Sensor> spec=(r,c,b)->b.conjunction();
        if(q!=null&&!q.isBlank()){String x="%"+q.strip().toLowerCase(Locale.ROOT)+"%";String term=q.strip();spec=spec.and((r,c,b)->{var matchesName=b.like(b.lower(r.get("name")),x);try{return b.or(matchesName,b.equal(r.get("number"),Integer.parseInt(term)));}catch(NumberFormatException ignored){return matchesName;}});}
        if(type!=null)spec=spec.and((r,c,b)->b.equal(r.get("type"),type));
        if(active!=null)spec=spec.and((r,c,b)->b.equal(r.get("active"),active));
        return sensors.findAll(spec,PageRequest.of(Math.max(0,page),Math.min(50,Math.max(1,size)),Sort.by("desc".equalsIgnoreCase(direction)?Sort.Direction.DESC:Sort.Direction.ASC,field)));
    }
    @Transactional(readOnly=true) public Sensor get(long id){return sensors.findById(id).orElseThrow(()->new NoSuchElementException("Sensor não encontrado."));}
    @Transactional @PreAuthorize("hasRole('ADMIN')") public Sensor create(SensorForm f){
        if(sensors.existsByTypeAndNumber(f.getType(),f.getNumber()))throw new IllegalArgumentException("Já existe um sensor desse tipo com este número.");
        return sensors.saveAndFlush(new Sensor(f.getName(),f.getType(),f.getNumber(),f.getDryAdc(),f.getWetAdc(),f.getPulsesPerLiter()));
    }
    @Transactional @PreAuthorize("hasRole('ADMIN')") public Sensor update(long id,SensorForm f){
        Sensor s=get(id);if(s.getType()!=f.getType()||!s.getNumber().equals(f.getNumber()))throw new IllegalArgumentException("Tipo e número do tópico são imutáveis.");
        s.update(f.getName(),f.getDryAdc(),f.getWetAdc(),f.getPulsesPerLiter(),f.isActive());return sensors.save(s);
    }
    @Transactional(readOnly=true) public Optional<SensorReading> latest(long id){return readings.findFirstBySensorIdOrderByMeasuredAtDescIdDesc(id);}
    @Transactional(readOnly=true) public Page<SensorReading> history(long id,Instant from,Instant to,Pageable p){get(id);return readings.findBySensorIdAndMeasuredAtBetween(id,from,to,p);}
    @Transactional(readOnly=true) public Map<Long,SensorReading> latestFor(List<Long> ids){
        if(ids.size()>50)throw new IllegalArgumentException("Até 50 sensores por consulta.");Map<Long,SensorReading> map=new HashMap<>();
        readings.findBySensorIdInOrderBySensorIdAscMeasuredAtDesc(ids).forEach(r->map.putIfAbsent(r.getSensor().getId(),r));return map;
    }
    @Transactional public boolean ingest(String topic,String json){
        SensorType type=Arrays.stream(SensorType.values()).filter(t->topic.matches("/"+t.prefix()+"/[1-9][0-9]*")).findFirst().orElse(null);if(type==null)return false;
        int n;try{n=Integer.parseInt(topic.substring(topic.lastIndexOf('/')+1));}catch(Exception e){return false;}
        Sensor s=sensors.findByTypeAndNumber(type,n).orElse(null);if(s==null||!s.isActive())return false;
        ReadingMessage.Data d=decoder.parse(json);if(d==null||readings.existsBySensorIdAndMessageId(s.getId(),d.id()))return false;
        long raw;Long interval=null;BigDecimal value;
        if(type==SensorType.UMIDADE){if(d.adc()==null||d.pulsos()!=null)return false;raw=d.adc();double p=100d*(s.getDryAdc()-raw)/(s.getDryAdc()-s.getWetAdc());value=BigDecimal.valueOf(Math.max(0,Math.min(100,p)));}
        else{if(d.pulsos()==null||d.adc()!=null||d.intervaloMs()==null||d.intervaloMs()<=0)return false;raw=d.pulsos();interval=d.intervaloMs();value=BigDecimal.valueOf(raw).multiply(BigDecimal.valueOf(60000)).divide(BigDecimal.valueOf(interval).multiply(s.getPulsesPerLiter()),4,RoundingMode.HALF_UP);}
        readings.save(new SensorReading(s,d.id(),d.medidoEm(),raw,interval,value));return true;
    }
}
