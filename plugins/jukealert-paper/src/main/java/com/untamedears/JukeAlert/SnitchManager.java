package com.untamedears.JukeAlert;

import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.Location;
import org.bukkit.block.Block;

import com.untamedears.JukeAlert.model.Snitch;
import com.untamedears.JukeAlert.model.SnitchChunkData;
import com.untamedears.JukeAlert.model.SnitchQTEntry;

import vg.civcraft.mc.civmodcore.locations.SparseQuadTree;
import vg.civcraft.mc.civmodcore.locations.chunkmeta.api.BlockBasedChunkMetaView;
import vg.civcraft.mc.civmodcore.locations.chunkmeta.block.table.TableBasedDataObject;
import vg.civcraft.mc.civmodcore.locations.chunkmeta.block.table.TableStorageEngine;

public class SnitchManager {

	private BlockBasedChunkMetaView<SnitchChunkData, TableBasedDataObject, TableStorageEngine<Snitch>> chunkData;
	private SparseQuadTree<SnitchQTEntry> quadTree;

	public SnitchManager(
			BlockBasedChunkMetaView<SnitchChunkData, TableBasedDataObject, TableStorageEngine<Snitch>> chunkData,
			SparseQuadTree<SnitchQTEntry> quadTree) {
		this.chunkData = chunkData;
		this.quadTree = quadTree;
	}

	public Snitch getSnitchAt(Location location) {
		return (Snitch) chunkData.get(location);
	}

	public Snitch getSnitchAt(Block block) {
		return (Snitch) chunkData.get(block);
	}

	public void addSnitch(Snitch snitch) {
		chunkData.put(snitch);
		for(SnitchQTEntry qt : snitch.getFieldManager().getQTEntries()) {
			quadTree.add(qt);
		}
	}
	
	public void removeSnitch(Snitch snitch) {
		chunkData.remove(snitch);
		for(SnitchQTEntry qt : snitch.getFieldManager().getQTEntries()) {
			quadTree.remove(qt);
		}
	}

	public Set<Snitch> getSnitchesCovering(Location location) {
		return quadTree.find(location.getBlockX(), location.getBlockZ(), true).stream()
				.map(SnitchQTEntry::getSnitch).filter(s -> s.getFieldManager().isInside(location))
				.collect(Collectors.toSet());
	}

}
