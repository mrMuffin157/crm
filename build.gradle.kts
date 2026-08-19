plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.exampleco"
version = "0.7.0-SNAPSHOT"
description = "crm"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // NotifyFailedTicketsService's daily digest never sends mail directly —
    // it publishes a MailTO (exampleco-core) onto the shared "message" exchange/routing key for
    // a separate mail-sending consumer elsewhere in the exampleco system to actually deliver.
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Quartz job scheduling (replacing plain @Scheduled) — its JDBC JobStore persists
    // into this same app-owned MySQL datasource via the hand-rolled QRTZ_* tables in
    // doc/0.6.0/create_quartz_tables.sql, not a separate/in-memory store.
    implementation("org.springframework.boot:spring-boot-starter-quartz")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    // Spring Boot 4 split WebClient's autoconfiguration (WebClient.Builder bean) out of
    // spring-boot-starter-webflux into its own starter — needed for LegacyApiClient.
    implementation("org.springframework.boot:spring-boot-starter-webclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    // Signs/verifies the JWTs this app issues to legacy-app at POST /oauth/access_token (see
    // LegacyServiceTokenService) — already pulled in transitively via spring-security-oauth2-jose,
    // declared explicitly here since this app now actively depends on it, not just Spring's own
    // OAuth2 client machinery.
    implementation("com.nimbusds:nimbus-jose-jwt")
    runtimeOnly("com.mysql:mysql-connector-j")
    // TicketCloseImportService's close-ticket CSV bulk import and
    // TicketReportCsvService's ticket report CSV export. Unrelated to the earlier commons-csv
    // dependency that backed the since-removed dev-only "csv run mode" (see that removal's own
    // history) — this is a real feature, not a revival of that scrapped path.
    implementation("org.apache.commons:commons-csv:1.11.0")
    // Real SalesOrderInfoTO/SalesOrderLineItemTO from the exampleco-core submodule (libs/exampleco-core,
    // see settings.gradle.kts's includeBuild) instead of hand-copied local DTOs.
    implementation("com.exampleco:exampleco-core")
    // Ticket update/create history — audits Ticket/TicketComment/TicketLineItem via
    // Hibernate's own @Audited/AuditReader. No explicit version: Spring Boot's dependency
    // management pins it to the same version as spring-boot-starter-data-jpa's Hibernate ORM.
    implementation("org.hibernate.orm:hibernate-envers")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-amqp-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testCompileOnly("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// TEMPORARY -- prints the resolved runtime classpath so tools.* main() classes (e.g.
// SalesOrderArchiveMigration) can be invoked with a plain `java -cp <this> <mainClass>` in a
// terminal, bypassing IntelliJ's Gradle-delegated "Run main()" (env vars set in that run
// configuration weren't reaching the process). Safe to delete once no longer needed.
tasks.register("printRuntimeClasspath") {
    doLast {
        println(sourceSets["main"].runtimeClasspath.asPath)
    }
}
