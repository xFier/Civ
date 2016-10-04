package com.devotedmc.ExilePearl;

import java.util.Date;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import com.devotedmc.ExilePearl.holder.PearlHolder;

/**
 * Interface for a player who is imprisoned in an exile pearl
 * @author Gordon
 */
public interface ExilePearl {
	
	/**
	 * Gets the pearl item name
	 * @return The item name
	 */
	String getItemName();

	/**
	 * Gets the exiled player ID
	 * @return The player ID
	 */
	UUID getPlayerId();
	
	/**
	 * Gets the unique pearl ID
	 * @return The pearl ID
	 */
	int getPearlId();

	/**
	 * Gets the exiled player instance
	 * @return The exiled player 
	 */
	PearlPlayer getPlayer();

	/**
	 * Gets when the player was pearled
	 * @return The time the player was pearled
	 */
	Date getPearledOn();

	/**
	 * Sets when the player was pearled
	 * @param pearledOn The time the player was pearled
	 */
	void setPearledOn(Date pearledOn);

	/**
	 * Gets the exiled player's name
	 * @return The exiled player's name
	 */
	String getPlayerName();

	/**
	 * Gets the pearl holder
	 * @return The pearl holder
	 */
	PearlHolder getHolder();

	/**
	 * Sets the pearl holder to a player
	 * @param player The new pearl holder
	 */
	void setHolder(PearlPlayer player);

	/**
	 * Sets the pearl holder to a block
	 * @param block The new pearl block
	 */
	void setHolder(Block block);

	/**
	 * Sets the pearl holder to a location
	 * @param item The new pearl item
	 */
	void setHolder(Item item);

    /**
     * Gets the pearl health value
     * @return The health value
     */
    int getHealth();

    /**
     * Gets the pearl health percent value
     * @return The health percent value
     */
    Integer getHealthPercent();
    
    /**
     * Sets the pearl health value
     * @param health The health value
     */
    void setHealth(int health);
    
    /**
     * Gets the location of the pearl
     * @return The location of the pearl
     */
	Location getLocation();
	
	/**
	 * Gets the ID of the killing player
	 * @return The ID of the killing player
	 */
	UUID getKillerUniqueId();
	
	/**
	 * Gets the name of the killing player
	 * @return The name of the killing player
	 */
	String getKillerName();

	/**
	 * Gets the string describing the pearl current location
	 * @return The description of the current location
	 */
	String getLocationDescription();

	/**
	 * Gets whether the player was freed offline
	 * @return true if the player was freed offline
	 */
	boolean getFreedOffline();

	/**
	 * Gets whether the player was freed offline
	 * @return true if the player was freed offline
	 */
	void setFreedOffline(boolean freedOffline);

	/**
	 * Creates an item stack for the pearl
	 * @return The new item stack
	 */
	ItemStack createItemStack();

	/**
	 * Verifies the pearl location
	 * @return true if the location is verified
	 */
	boolean verifyLocation();
	
	/**
	 * Validates that an item stack is the exile pearl
	 * @param is The item stack
	 * @return true if validation passes
	 */
	boolean validateItemStack(ItemStack is);
	
	/**
	 * Enables storage updates when writing values
	 */
	void enableStorage();
}
