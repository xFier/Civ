plugins {
    id("java-library")
}

dependencies {
    // Compile-only: the ConfigSerializable annotation is read reflectively by the proxy, which has
    // Configurate on its classpath already. A server receives regions as JSON and never parses config
    compileOnly(libs.configurate.yaml)
    api(libs.gson)
}
