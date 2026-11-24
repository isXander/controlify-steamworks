plugins {
    id("java")
    `maven-publish`
}

group = "dev.isxander.steam"

val buildNumber = System.getenv("GITHUB_RUN_NUMBER") ?: "local"
version = "1.65-$buildNumber"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.1.0")
}

java {
    withSourcesJar()
    withJavadocJar()

    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

publishing {
    repositories {
        maven("https://maven.isxander.dev/releases") {
            name = "XanderMaven"
            credentials(PasswordCredentials::class)
        }
    }
    publications {
        register<MavenPublication>("ffm") {
            from(components["java"])
        }
    }
}