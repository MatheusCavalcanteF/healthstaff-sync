package br.com.healthstaff.backend.regras;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RegrasProperties.class)
class RegrasConfig {
}
