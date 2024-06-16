import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.5"
    id("maven-publish")
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
}

group = "com.littleetx"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Jar> {
    enabled = true
    archiveClassifier = ""
}


publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            url = uri(repositories.mavenLocal().url)
        }
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
