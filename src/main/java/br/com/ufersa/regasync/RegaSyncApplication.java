package br.com.ufersa.regasync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class RegaSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegaSyncApplication.class, args);
    }

}
