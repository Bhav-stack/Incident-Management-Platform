import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "io.aegis"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    plugins.withId("java") {
        // Java 21 toolchain — Gradle auto-provisions a JDK 21 if the machine
        // only has another version (foojay resolver in settings.gradle.kts).
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        // Tests must not depend on the developer's clock settings. The JDBC
        // driver sends the JVM's default time-zone name to PostgreSQL in the
        // connection startup packet, and modern images ship a tzdata that no
        // longer carries legacy aliases such as "Asia/Calcutta". Tests run in
        // UTC the same way the deployed containers do.
        systemProperty("user.timezone", "UTC")
    }
}