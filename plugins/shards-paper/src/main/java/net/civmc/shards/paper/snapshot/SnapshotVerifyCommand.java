package net.civmc.shards.paper.snapshot;

import java.util.List;
import net.civmc.shards.api.snapshot.PlayerSnapshot;
import net.civmc.shards.api.snapshot.PlayerSnapshotCodec;
import net.civmc.shards.api.snapshot.PlayerSnapshotDiff;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Checks that a player survives a snapshot round trip unchanged.
 *
 * <p>Captures the sender, puts the snapshot through the same serialization the payload column uses,
 * restores it onto them, captures again, and reports every field that came back different. There is
 * no server to run this against automatically and no CI, so this command is how the snapshot is
 * verified - and a field that fails to round-trip is otherwise invisible, because the symptom is the
 * destination's stale value surviving rather than an error.</p>
 *
 * <p>Nothing is wiped in between. Restoring a player onto themselves is a no-op when the snapshot is
 * faithful, which is exactly what is being tested, and it means a mistake here cannot cost anyone
 * their inventory.</p>
 */
public final class SnapshotVerifyCommand implements CommandExecutor {

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can be snapshotted", NamedTextColor.RED));
            return true;
        }

        final PlayerSnapshot before = PlayerSnapshots.capture(player);
        final byte[] payload = PlayerSnapshotCodec.toBytes(before);
        final PlayerSnapshot parsed = PlayerSnapshotCodec.fromBytes(payload);
        if (parsed == null) {
            sender.sendMessage(Component.text("Snapshot serialized to nothing", NamedTextColor.RED));
            return true;
        }

        PlayerSnapshots.restore(player, parsed);
        final PlayerSnapshot after = PlayerSnapshots.capture(player);

        final List<String> differences = PlayerSnapshotDiff.describe(before, after);
        sender.sendMessage(Component.text("Snapshot payload: " + payload.length + " bytes", NamedTextColor.GRAY));
        if (differences.isEmpty()) {
            sender.sendMessage(Component.text("Round trip faithful, no field changed", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text(differences.size() + " field(s) did not survive the round trip",
                NamedTextColor.RED));
            for (final String difference : differences) {
                sender.sendMessage(Component.text("  " + difference, NamedTextColor.YELLOW));
            }
        }
        sender.sendMessage(Component.text("Not carried by design: "
            + String.join(", ", PlayerSnapshot.NOT_CARRIED), NamedTextColor.GRAY));
        return true;
    }
}
