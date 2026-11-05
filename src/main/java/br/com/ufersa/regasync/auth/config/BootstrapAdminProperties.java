package br.com.ufersa.regasync.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.bootstrap-admin")
public record BootstrapAdminProperties(String name, String email, String password) {
    @Override
    public String toString() {
        return "BootstrapAdminProperties[credentials=REDACTED]";
    }
}
