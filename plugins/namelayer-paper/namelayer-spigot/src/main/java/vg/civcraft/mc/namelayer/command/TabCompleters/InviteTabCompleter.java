package vg.civcraft.mc.namelayer.command.TabCompleters;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;

import vg.civcraft.mc.namelayer.NameAPI;
import vg.civcraft.mc.namelayer.group.Group;
import vg.civcraft.mc.namelayer.listeners.PlayerListener;

/**
 * Created by isaac on 2/2/2015.
 */
public class InviteTabCompleter {
    public static List<String> complete(String lastArg, Player sender) {
        UUID uuid = NameAPI.getUUID(sender.getName());
        List<Group> groups = PlayerListener.getNotifications(uuid);
        List<String> result = new LinkedList<>();
        
        if (groups == null)
            return new ArrayList<>();

        for (Group group : groups){
            if (lastArg == null || group.getName().toLowerCase().startsWith(lastArg.toLowerCase())){
                result.add(group.getName());
            }
        }

        return result;
    }
}
