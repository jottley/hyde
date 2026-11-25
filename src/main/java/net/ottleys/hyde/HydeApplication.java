package net.ottleys.hyde;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HydeApplication {

    public static void main(String[] args) {
        SpringApplication.run(HydeApplication.class, args);
    }
}
