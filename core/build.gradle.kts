dependencies {
    compileOnly(libs.paper.v11605)
    compileOnly(libs.log4j.core)
    compileOnly("com.google.code.gson:gson:2.10.1")

    testImplementation(kotlin("stdlib"))
    testImplementation(rootProject.libs.packetevents)
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation(libs.paper.v11605)
    testImplementation(libs.pv.lavaplayer)
    testImplementation("net.kyori:adventure-text-serializer-plain:4.17.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")

    api(platform(libs.koin.bom))
    api(libs.koin.core)
    api(libs.reflectionremapper)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.test { useJUnitPlatform() }
