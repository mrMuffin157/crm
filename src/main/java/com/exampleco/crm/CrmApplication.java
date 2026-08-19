package com.exampleco.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.exampleco.crm.config.auth.KeycloakProperties;
import com.exampleco.crm.config.auth.LegacyServiceAuthProperties;
import com.exampleco.crm.config.salesorder.LegacyApiProperties;
import com.exampleco.crm.config.ticket.ReplacementShippedMailProperties;

@SpringBootApplication
@EnableConfigurationProperties({KeycloakProperties.class, LegacyApiProperties.class, LegacyServiceAuthProperties.class,
        ReplacementShippedMailProperties.class})
public class CrmApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrmApplication.class, args);
    }

}
