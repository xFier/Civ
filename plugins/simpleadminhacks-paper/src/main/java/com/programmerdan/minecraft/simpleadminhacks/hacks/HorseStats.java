package com.programmerdan.minecraft.simpleadminhacks.hacks;

import com.programmerdan.minecraft.simpleadminhacks.SimpleAdminHacks;
import com.programmerdan.minecraft.simpleadminhacks.SimpleHack;
import com.programmerdan.minecraft.simpleadminhacks.configs.HorseStatsConfig;
import com.programmerdan.minecraft.simpleadminhacks.configs.ToggleLampConfig;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import vg.civcraft.mc.citadel.Citadel;

public class HorseStats extends SimpleHack<HorseStatsConfig> implements Listener {

	public HorseStats(SimpleAdminHacks plugin, HorseStatsConfig config) {
		super(plugin, config);
		plugin().log("Instanciating HS");
	}

	@EventHandler(priority = EventPriority.NORMAL)
	public void onHorseStatCheck(PlayerInteractEntityEvent event) {
		plugin().log("Active");
		if (!config.isEnabled()) {
			return;
		}
		plugin().log("Enabled");
		ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
		if (item.getType() != Material.COMPASS) {
			return;
		}
		Entity entity = event.getRightClicked();
		if (!(entity instanceof AbstractHorse)) {
			return;
		}
		AbstractHorse horse = (AbstractHorse)entity;
		AttributeInstance attrHealth = horse.getAttribute(Attribute.GENERIC_MAX_HEALTH);
		AttributeInstance attrSpeed = horse.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
		event.getPlayer().sendMessage(String.format("%sHealth = %f, Speed = %f, Jump height = %f",
				ChatColor.YELLOW,
				attrHealth.getBaseValue(),
				attrSpeed.getBaseValue(),
				horse.getJumpStrength()));
	}

	@Override
	public void registerListeners() {
		if (config.isEnabled()) {
			plugin().log("Registering HorseStats listeners");
			plugin().registerListener(this);
		}
	}

	@Override
	public void registerCommands() {

	}

	@Override
	public void dataBootstrap() {

	}

	@Override
	public void unregisterListeners() {

	}

	@Override
	public void unregisterCommands() {

	}

	@Override
	public void dataCleanup() {

	}

	public static HorseStatsConfig generate(SimpleAdminHacks plugin, ConfigurationSection config) {
		return new HorseStatsConfig(plugin, config);
	}

	@Override
	public String status() {
		return config.isEnabled() ? "HorseStats enabled." : "HorseStats disabled.";
	}
}
