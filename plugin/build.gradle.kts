plugins {
    java
}

group = "dev.minecraftharness"
version = "0.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Compile against 1.21.4 by default. CI also compiles with -PpaperApi=<26.x version> to catch API drift.
val paperApi = (findProperty("paperApi") as String?) ?: "1.21.4-R0.1-SNAPSHOT"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")

    testImplementation("io.papermc.paper:paper-api:$paperApi")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("MinecraftHarness")
    archiveVersion.set(project.version.toString())
}
