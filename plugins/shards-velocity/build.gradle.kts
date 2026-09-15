plugins {
    alias(libs.plugins.shadow)
}

version = "1.0.0"

dependencies {
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)

    api(libs.configurate.yaml)
    api(libs.hikaricp)
    api(libs.mariadb.client)
    api(libs.jdbi.core) {
        exclude(group = "org.slf4j") // provided by Velocity
    }
    api(libs.jdbi.sqlobject) {
        exclude(group = "org.slf4j")
    }
}

tasks.withType<JavaCompile> {
    // Keeps method parameter names so JDBI can bind :playerUuid to a playerUuid parameter without @Bind
    options.compilerArgs.add("-parameters")
}

tasks.shadowJar {
    // Configurate keeps its record support in Java 16+ versioned classes, which the JVM only uses when the jar
    // is marked Multi-Release; shading drops that flag from Configurate's own manifest
    manifest {
        attributes["Multi-Release"] = "true"
    }
    // Relocated so another proxy plugin shipping a different JDBI version can't clash with ours
    relocate("org.jdbi", "net.civmc.shards.libs.jdbi")
    relocate("io.leangen.geantyref", "net.civmc.shards.libs.geantyref")
}
