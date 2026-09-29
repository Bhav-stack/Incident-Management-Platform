package io.aegis.incident;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class IncidentApplication {

    public static void main(String[] args) {
        // UTC everywhere: the JDBC driver forwards the JVM's default time-zone
        // name when it opens a connection, and modern PostgreSQL images no
        // longer know legacy aliases such as "Asia/Calcutta", which makes the
        // service fail to start on a machine set to that zone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(IncidentApplication.class, args);
    }
}