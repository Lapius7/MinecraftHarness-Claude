plugins {
    java
}

group = "dev.minecraftharness"
version = "0.1.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.java-websocket:Java-WebSocket:1.5.7")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("MinecraftHarness")
    archiveVersion.set(project.version.toString())
}
