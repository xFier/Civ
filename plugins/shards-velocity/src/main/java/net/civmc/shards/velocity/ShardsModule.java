package net.civmc.shards.velocity;

import com.google.inject.AbstractModule;
import net.civmc.shards.velocity.config.ShardsConfig;
import net.civmc.shards.velocity.database.DatabaseModule;

public final class ShardsModule extends AbstractModule {

    private final ShardsConfig config;

    public ShardsModule(final ShardsConfig config) {
        this.config = config;
    }

    @Override
    protected void configure() {
        bind(ShardsConfig.class).toInstance(this.config);
        install(new DatabaseModule());
    }
}
