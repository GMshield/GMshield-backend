import org.springframework.boot.gradle.plugin.SpringBootPlugin

// Versions: the newest on Maven Central on 2026-11-12 (see README.md, "Versions").
plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.2.0-RC2"
}

val springModulithVersion = "2.2.0-RC1"
val springdocVersion = "3.2.0"
val pdfboxVersion = "3.0.8"

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

// Integration tests (Story 1.11): run against a real PostgreSQL and Redis at the test-mode hosts
// (postgres:5432, redis:6379), never by `check`. The pipeline's integration stage runs them in the
// Dockerfile's `integration` stage beside throwaway services. The unit tests' classes are on their classpath too:
// CrossOwnerAuditIntegrationTest runs CrossOwnerAuditFlowTest's routes, probes and worlds on PostgreSQL, shared
// rather than copied (Gradle runs only this source set's own test classes). A bean in the test sources must be
// @Profile("unit"), @TestConfiguration or @TestComponent, or component scanning puts it in every integration context.
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:$springdocVersion")
    // Story 5.7: the artifact exports (PDF, and JPG from the PDF's pages), rendered in memory (see README.md).
    implementation("org.apache.pdfbox:pdfbox:$pdfboxVersion")
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

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs the integration tests against the test-mode PostgreSQL and Redis."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    // Every run talks to the services: never up to date, never taken from the build cache.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // No coverage agent: the container that runs them is offline, and the unit tests measure coverage.
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
}

// Downloads every jar the integration tests run with: the Dockerfile's `integration` stage runs them offline.
val integrationTestDependencies by tasks.registering {
    description = "Resolves the integration tests' runtime classpath."
    val runtime = configurations.named(integrationTest.runtimeClasspathConfigurationName)
    inputs.files(runtime)
    doLast {
        logger.lifecycle("integration test classpath: {} files", runtime.get().files.size)
    }
}

// `check` compiles the integration tests (it does not run them: they need the services).
tasks.check {
    dependsOn(integrationTest.classesTaskName)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = false
    }
}

// The dependency jars, for SonarQube to resolve the types the code uses (GMshield-pipeline passes
// sonar.java.libraries when the test-reports stage exports libraries/). The Dockerfile's test stage runs it.
val sonarLibraries by tasks.registering(Sync::class) {
    from(configurations.testRuntimeClasspath.map { classpath -> classpath.filter { it.name.endsWith(".jar") } })
    into(layout.buildDirectory.dir("sonar-libraries"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
