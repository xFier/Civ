plugins {
    alias(libs.plugins.shadow)
}

version = "1.0.0"

dependencies {
    compileOnly(libs.paper.api)
    // Showing a player who is on another shard means sending a player entity that does not exist
    // here, which the public API has no way to express. Chosen over ProtocolLib by precedent: it is
    // already a dependency of citadel, finale and simpleadminhacks, and already runs on the servers
    compileOnly(libs.packetevents.spigot)

    api(project(":libraries:shards-api"))
    api(libs.rabbitmq.client)
}
