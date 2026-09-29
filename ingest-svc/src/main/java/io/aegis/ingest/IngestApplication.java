package io.aegis.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class IngestApplication {

    public static void main(String[] args) {
        // See IncidentApplication: the JDBC driver forwards the JVM's default
        // time-zone name, and legacy aliases are rejected by modern tzdata.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(IngestApplication.class, args);
    }
}