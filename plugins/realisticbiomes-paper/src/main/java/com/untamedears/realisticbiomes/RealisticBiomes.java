package com.untamedears.realisticbiomes;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;

import com.untamedears.realisticbiomes.listener.AnimalListener;
import com.untamedears.realisticbiomes.listener.DropListener;
import com.untamedears.realisticbiomes.listener.PlantListener;
import com.untamedears.realisticbiomes.listener.PlayerListener;
import com.untamedears.realisticbiomes.model.Plant;
import com.untamedears.realisticbiomes.model.RBChunkCache;
import com.untamedears.realisticbiomes.model.RBDAO;

import vg.civcraft.mc.civmodcore.ACivMod;
import vg.civcraft.mc.civmodcore.CivModCorePlugin;
import vg.civcraft.mc.civmodcore.locations.chunkmeta.api.BlockBasedChunkMetaView;
import vg.civcraft.mc.civmodcore.locations.chunkmeta.api.ChunkMetaAPI;
import vg.civcraft.mc.civmodcore.locations.chunkmeta.block.table.TableBasedDataObject;
import vg.civcraft.mc.civmodcore.locations.chunkmeta.block.table.TableStorageEngine;

public class RealisticBiomes extends ACivMod {

	private static RealisticBiomes plugin;

	public static RealisticBiomes getInstance() {
		return plugin;
	}

	private GrowthConfigManager growthConfigManager;
	private RBConfigManager configManager;
	private PlantManager plantManager;
	private AnimalConfigManager animalManager;
	private PlantLogicManager plantLogicManager;
	private PlantProgressManager plantProgressManager;
	private BlockDropManager blockDropManager;

	private RBDAO dao;

	public RBConfigManager getConfigManager() {
		return configManager;
	}

	public RBDAO getDAO() {
		return dao;
	}

	public GrowthConfigManager getGrowthConfigManager() {
		return growthConfigManager;
	}

	public PlantLogicManager getPlantLogicManager() {
		return plantLogicManager;
	}

	public PlantManager getPlantManager() {
		return plantManager;
	}

	public PlantProgressManager getPlantProgressManager() {
		return plantProgressManager;
	}
	
	public BlockDropManager getBlockDropManager() {
		return blockDropManager;
	}

	@Override
	public void onDisable() {
		dao.setBatchMode(true);
		if (plantManager != null) {
			plantManager.shutDown();
		}
		dao.cleanupBatches();
		blockDropManager.shutdown();
	}

	@Override
	public void onEnable() {
		this.useNewCommandHandler = true;
		super.onEnable();
		RealisticBiomes.plugin = this;
		configManager = new RBConfigManager(this);
		if (!configManager.parse()) {
			return;
		}
		animalManager = new AnimalConfigManager();
		growthConfigManager = new GrowthConfigManager(configManager.getPlantGrowthConfigs());
		if (configManager.hasPersistentGrowthConfigs()) {
			this.dao = new RBDAO(getLogger(), configManager.getDatabase());
			if (!dao.updateDatabase()) {
				Bukkit.shutdown();
				return;
			}
			blockDropManager = new BlockDropManager(CivModCorePlugin.getInstance().getDatabase());
			if (!blockDropManager.setup()) {
				Bukkit.shutdown();
				return;
			}
			plantProgressManager = new PlantProgressManager();
			BlockBasedChunkMetaView<RBChunkCache, TableBasedDataObject, TableStorageEngine<Plant>> chunkMetaData = ChunkMetaAPI
					.registerBlockBasedPlugin(this, () -> {
						return new RBChunkCache(false, dao);
					}, dao, false);
			if (chunkMetaData == null) {
				getLogger().severe("Errors setting up chunk metadata API, shutting down");
				Bukkit.shutdown();
				return;
			}
			plantManager = new PlantManager(chunkMetaData);
		}
		plantLogicManager = new PlantLogicManager(plantManager, growthConfigManager);
		registerListeners();
	}

	private void registerListeners() {
		PluginManager pm = getServer().getPluginManager();
		pm.registerEvents(new PlantListener(this), this);
		pm.registerEvents(new AnimalListener(animalManager), this);
		pm.registerEvents(new PlayerListener(growthConfigManager, animalManager, plantManager), this);
		pm.registerEvents(new DropListener(blockDropManager), this);
	}

}
