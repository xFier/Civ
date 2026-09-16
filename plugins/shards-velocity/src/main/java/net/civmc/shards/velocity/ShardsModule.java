package net.civmc.shards.velocity;

import com.google.inject.AbstractModule;
import net.civmc.shards.velocity.config.ShardsConfig;
import net.civmc.shards.velocity.database.DatabaseModule;

public final class ShardsModule extends AbstractModule {

    private final ShardsConfig shardsConfig;

    public ShardsModule(final ShardsConfig shardsConfig) {
        this.shardsConfig = shardsConfig;
    }

    @Override
    protected void configure() {
        bind(ShardsConfig.class).toInstance(this.shardsConfig);
        install(new DatabaseModule());
    }
}
