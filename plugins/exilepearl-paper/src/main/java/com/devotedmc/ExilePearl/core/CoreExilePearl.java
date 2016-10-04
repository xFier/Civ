package com.devotedmc.ExilePearl.core;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import com.devotedmc.ExilePearl.ExilePearl;
import com.devotedmc.ExilePearl.ExilePearlApi;
import com.devotedmc.ExilePearl.PearlPlayer;
import com.devotedmc.ExilePearl.event.PearlMovedEvent;
import com.devotedmc.ExilePearl.holder.BlockHolder;
import com.devotedmc.ExilePearl.holder.ItemHolder;
import com.devotedmc.ExilePearl.holder.PearlHolder;
import com.devotedmc.ExilePearl.holder.HolderVerifyResult;
import com.devotedmc.ExilePearl.holder.PlayerHolder;
import com.devotedmc.ExilePearl.storage.PearlUpdateStorage;
import com.devotedmc.ExilePearl.util.Guard;

/**
 * Instance of a player who is imprisoned in an exile pearl
 * @author Gordon
 */
class CoreExilePearl implements ExilePearl {
	private static final int HOLDER_COUNT = 5;
	private static String ITEM_NAME = "Exile Pearl";
	private static final int DEFAULT_HEALTH = 10;

	// The player provider instance
	private final ExilePearlApi pearlApi;
	
	// The storage instance
	private final PearlUpdateStorage storage;
	
	// The ID of the exiled player
	private final UUID playerId;
	
	// The ID of the player who killed the exiled player
	private final UUID killedBy;
	
	// The unique player ID
	private final int pearlId;
	
	private PearlPlayer player;
	private PearlHolder holder;
	private Date pearledOn;
	private LinkedBlockingDeque<PearlHolder> holders;
	private boolean freedOffline;
	private int health;
	private boolean storageEnabled;

	/**
	 * Creates a new prison pearl instance
	 * @param playerId The pearled player id
	 * @param holder The holder instance
	 */
	public CoreExilePearl(final ExilePearlApi pearlApi, final PearlUpdateStorage storage, 
			final UUID playerId, final UUID killedBy, int pearlId, final PearlHolder holder) {
		Guard.ArgumentNotNull(pearlApi, "pearlApi");
		Guard.ArgumentNotNull(storage, "storage");
		Guard.ArgumentNotNull(playerId, "playerId");
		Guard.ArgumentNotNull(killedBy, "killedBy");
		Guard.ArgumentNotNull(holder, "holder");
		
		this.pearlApi = pearlApi;
		this.storage = storage;
		this.playerId = playerId;
		this.pearlId = pearlId;
		this.killedBy = killedBy;
		this.pearledOn = new Date();
		this.holders = new LinkedBlockingDeque<PearlHolder>();
		this.holder = holder;
		this.holders.add(holder);
		this.health = DEFAULT_HEALTH;
		storageEnabled = false;
	}


	/**
	 * Gets the imprisoned player ID
	 * @return The player ID
	 */
	@Override
	public UUID getPlayerId() {
		return playerId;
	}
	

	@Override
	public int getPearlId() {
		return pearlId;
	}


	/**
	 * Gets the imprisoned player
	 * @return The player instance
	 */
	@Override
	public PearlPlayer getPlayer() {
		if (player == null) {
			player = pearlApi.getPearlPlayer(playerId);
		}
		return player;
	}


	/**
	 * Gets when the player was pearled
	 * @return The time the player was pearled
	 */
	@Override
	public Date getPearledOn() {
		return this.pearledOn;
	}


	/**
	 * Sets when the player was pearled
	 * @param pearledOn The time the player was pearled
	 */
	@Override
	public void setPearledOn(Date pearledOn) {
		Guard.ArgumentNotNull(pearledOn, "pearledOn");
		checkPearlValid();
		
		this.pearledOn = pearledOn;
	}


	/**
	 * Gets the imprisoned name
	 * @return The player name
	 */
	@Override
	public String getPlayerName() {
		return this.getPlayer().getName();
	}


	/**
	 * Gets the pearl holder
	 * @return The pearl holder
	 */
	@Override
	public PearlHolder getHolder() {
		return this.holder;
	}


	/**
	 * Sets the pearl holder to a player
	 * @param holder The new pearl holder
	 */
	public void setHolder(PearlHolder holder) {
		Guard.ArgumentNotNull(holder, "holder");
		setHolderInternal(holder);
	}


	/**
	 * Sets the pearl holder to a player
	 * @param player The new pearl holder
	 */
	@Override
	public void setHolder(PearlPlayer player) {
		Guard.ArgumentNotNull(player, "player");
		setHolderInternal(new PlayerHolder(player.getPlayer()));
	}


	/**
	 * Sets the pearl holder to a block
	 * @param block The new pearl block
	 */
	@Override
	public void setHolder(Block block) {
		Guard.ArgumentNotNull(block, "block");
		setHolderInternal(new BlockHolder(block));
	}


	/**
	 * Sets the pearl holder to a location
	 * @param location The new pearl location
	 */
	@Override
	public void setHolder(Item item) {
		Guard.ArgumentNotNull(item, "item");
		setHolderInternal(new ItemHolder(item));
	}
	
	
	/**
	 * Internal method for updating the holder
	 * @param holder The new holder instance
	 */
	private void setHolderInternal(PearlHolder holder) {
		checkPearlValid();
		
		// Do nothing if the holder is the same
		if (holder.equals(holders.getLast())) {
			return;
		}
		
		// Generate a moved event
		Bukkit.getPluginManager().callEvent(new PearlMovedEvent(this, this.holder, holder));
		
		this.holder = holder;
		this.holders.add(holder);

		if (holders.size() > HOLDER_COUNT) {
			holders.poll();
		}

		if(storageEnabled) {
			storage.pearlUpdateLocation(this);
		}
	}

    
    /**
     * Gets the pearl health value
     * @return The strength value
     */
	@Override
    public Integer getHealthPercent() {
		return (int)Math.round(((double)health / pearlApi.getPearlConfig().getPearlHealthMaxValue()) * 100);
    }

    
    /**
     * Gets the pearl health value
     * @return The strength value
     */
	@Override
    public int getHealth() {
    	return this.health;
    }
    
    
    /**
     * Sets the pearl heatlh value
     * @param The strength value
     */
	@Override
    public void setHealth(int health) {
		checkPearlValid();
		
    	if (health < 0) {
    		health = 0;
    	}
    	
    	if (health > pearlApi.getPearlConfig().getPearlHealthMaxValue()) {
    		health = pearlApi.getPearlConfig().getPearlHealthMaxValue();
    	}
    	
    	this.health = health;
    	
		if(storageEnabled) {
			storage.pearlUpdateHealth(this);
		}
    }

	/**
	 * Gets the pearl location
	 */
	@Override
	public Location getLocation() {
		return this.holders.peekLast().getLocation();
	}


	@Override
	public String getItemName() {
		return ITEM_NAME;
	}


	@Override
	public UUID getKillerUniqueId() {
		return killedBy;
	}


	@Override
	public String getKillerName() {
		String name = pearlApi.getPearlPlayer(killedBy).getName();
		if (name == null) {
			name = "Unknown player";
		}
		return name;
	}


	/**
	 * Gets the name of the current location
	 * @return The string of the current location
	 */
	@Override
	public String getLocationDescription() {
		final Location loc = holder.getLocation();
		final Vector vec = loc.toVector();
		final String str = loc.getWorld().getName() + " " + vec.getBlockX() + " " + vec.getBlockY() + " " + vec.getBlockZ();
		return "held by " + holder.getName() + " at " + str;
	}


	/**
	 * Gets whether the player was freed offline
	 * @return true if the player was freed offline
	 */
	@Override
	public boolean getFreedOffline() {
		return this.freedOffline;
	}

	/**
	 * Gets whether the player was freed offline
	 * @return true if the player was freed offline
	 */
	@Override
	public void setFreedOffline(boolean freedOffline) {
		checkPearlValid();
		
		this.freedOffline = freedOffline;
		
		if (storageEnabled) {
			storage.pearlUpdateFreedOffline(this);
		}
	}


	/**
	 * Creates an item stack for the pearl
	 * @return The new item stack
	 */
	@Override
	public ItemStack createItemStack() {
		List<String> lore = pearlApi.getLoreGenerator().generateLore(this);
		ItemStack is = new ItemStack(Material.ENDER_PEARL, 1);
		ItemMeta im = is.getItemMeta();
		im.setDisplayName(this.getPlayerName());
		im.setLore(lore);
		im.addEnchant(Enchantment.DURABILITY, 1, true);
		im.addItemFlags(ItemFlag.HIDE_ENCHANTS);
		is.setItemMeta(im);
		return is;
	}


	/**
	 * Validates that an item stack is the prison pearl
	 * @param is The item stack
	 * @return true if it checks out
	 */
	public boolean validateItemStack(ItemStack is) {
		Guard.ArgumentNotNull(is, "is");

		int pearlId = pearlApi.getLoreGenerator().getPearlIdFromItemStack(is);
		
		if (pearlId == this.pearlId) {

			// re-create the item stack to update the values
			ItemMeta im = is.getItemMeta();
			im.setLore(pearlApi.getLoreGenerator().generateLore(this));
			is.setItemMeta(im);
			return true;
		}

		return false;
	}


	/**
	 * Verifies the pearl location
	 * @return
	 */
	public boolean verifyLocation() {
		StringBuilder sb = new StringBuilder();

		StringBuilder failure_reason_log = new StringBuilder();

		for (final PearlHolder holder : this.holders) {
			HolderVerifyResult reason = this.verifyHolder(holder);
			if (reason.isValid()) {
				sb.append(String.format("ExilePearl (%s, %s) passed verification for reason '%s'.",
						playerId.toString(), this.getPlayerName(), reason.toString()));
				pearlApi.log(sb.toString());

				return true;
			} else {
				failure_reason_log.append(reason.toString()).append(", ");
			}
		}
		sb.append(String.format("ExilePearl (%s, %s) failed verification for reason '%s'.",
				playerId.toString(), this.getPlayerName(), failure_reason_log.toString()));

		pearlApi.log(sb.toString());
		return false;
	}


	/**
	 * Verifies the holder of a pearl
	 * @param holder The holder to check
	 * @param feedback The feedback string
	 * @return true if the pearl was found in a valid location
	 */
	private HolderVerifyResult verifyHolder(PearlHolder holder) {
		return holder.validate(this);
	}


	/**
	 * Gets the item stack from an inventory if it exists
	 * @param inv The inventory to search
	 * @return The pearl item
	 */
	public ItemStack getItemFromInventory(Inventory inv) {
		Guard.ArgumentNotNull(inv, "inv");

		for (ItemStack item : inv.all(Material.ENDER_PEARL).values()) {
			if (this.validateItemStack(item)) {
				return item;
			}
		}

		return null;
	}


	@Override
	public void enableStorage() {
		storageEnabled = true;
	}
	
	/**
	 * Checks to make sure the pearl being operated on is valid
	 */
	private void checkPearlValid() {
		if (storageEnabled && !pearlApi.isPlayerExiled(playerId)) {
			throw new RuntimeException(String.format("Tried to modify exile pearl for player %s that is no longer valid.", getPlayerName()));
		}
	}
	
    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 31) // two randomly chosen prime numbers
            .append(playerId)
            .append(killedBy)
            .append(getLocation())
            .append(health)
            .append(pearledOn)
            .append(freedOffline)
            .toHashCode();
    }
	
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        CoreExilePearl other = (CoreExilePearl) o;

		return new EqualsBuilder()
				.append(playerId, other.playerId)
				.append(killedBy, other.killedBy)
				.append(getLocation(), other.getLocation())
				.append(health, other.health)
				.append(pearledOn, other.pearledOn)
				.append(freedOffline, other.freedOffline)
				.isEquals();
    }
}
