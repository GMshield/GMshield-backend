import org.springframework.boot.gradle.plugin.SpringBootPlugin

// Versions: the newest on Maven Central on 2026-11-12 (see README.md, "Versions").
plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.2.0-RC2"
}

val springModulithVersion = "2.2.0-RC1"
val springdocVersion = "3.2.0"

group = "io.github.gmshield"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(27)
    }
}

repositories {
    mavenCentral()
}

// Small module trees that break the module rules on purpose. They live in their own source set,
// outside the application's base package, so that the module verification of the application
// never sees them and the boundary test can prove that the verification really fails.
val boundaryFixtures: SourceSet by sourceSets.creating

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:$springdocVersion")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    "boundaryFixturesImplementation"(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))
    "boundaryFixturesImplementation"("org.springframework.modulith:spring-modulith-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation(boundaryFixtures.output)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jacoco {
    toolVersion = "0.8.16"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveFileName = "gmshield-backend.jar"
}

tasks.test {
    useJUnitPlatform()
    // The committed OpenAPI documents are an input: changing one must run the drift test again.
    inputs.dir("openapi").withPathSensitivity(PathSensitivity.RELATIVE)
    // `./gradlew test -PupdateOpenApi` rewrites openapi/*.json instead of failing on a difference.
    systemProperty("gmshield.openapi.update", providers.gradleProperty("updateOpenApi").isPresent)
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = false
    }
}
