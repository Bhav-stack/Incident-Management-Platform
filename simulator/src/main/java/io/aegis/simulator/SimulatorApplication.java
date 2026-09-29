package io.aegis.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SimulatorApplication {

    public static void main(String[] args) {
        // Emitted timestamps must be UTC so correlation and dedup windows are
        // independent of the host clock settings.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(SimulatorApplication.class, args);
    }
}