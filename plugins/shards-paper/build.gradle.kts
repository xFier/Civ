plugins {
    alias(libs.plugins.shadow)
}

version = "1.0.0"

dependencies {
    compileOnly(libs.paper.api)

    api(project(":libraries:shards-api"))
    api(libs.rabbitmq.client)
}
