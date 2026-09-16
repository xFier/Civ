package net.civmc.shards.api.snapshot;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reports every field two snapshots disagree on.
 *
 * <p>This exists because the way a snapshot fails is that one field quietly keeps the destination
 * server's value. Nothing throws and nothing looks wrong, so the only way to know a round trip was
 * faithful is to compare the whole record rather than spot-check an inventory.</p>
 *
 * <p>Fields are found reflectively over the record's components, so a component added later is
 * compared without anyone remembering to add it here - which is the case that would otherwise go
 * unnoticed.</p>
 */
public final class PlayerSnapshotDiff {

    private PlayerSnapshotDiff() {
    }

    /**
     * @return one line per differing field, empty when the two are identical
     */
    public static List<String> describe(final PlayerSnapshot before, final PlayerSnapshot after) {
        final List<String> differences = new ArrayList<>();
        for (final RecordComponent component : PlayerSnapshot.class.getRecordComponents()) {
            final Object left = read(component, before);
            final Object right = read(component, after);
            if (Objects.equals(left, right)) {
                continue;
            }
            differences.add(describeComponent(component.getName(), left, right));
        }
        return differences;
    }

    private static String describeComponent(final String name, final Object left, final Object right) {
        if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
            return name + ": " + describeMap(leftMap, rightMap);
        }
        if (left instanceof Collection<?> leftItems && right instanceof Collection<?> rightItems) {
            return name + ": " + describeCollection(leftItems, rightItems);
        }
        return name + ": " + abbreviate(left) + " -> " + abbreviate(right);
    }

    private static String describeMap(final Map<?, ?> before, final Map<?, ?> after) {
        final Set<Object> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        final List<String> entries = new ArrayList<>();
        for (final Object key : keys) {
            final Object left = before.get(key);
            final Object right = after.get(key);
            if (!Objects.equals(left, right)) {
                entries.add(key + " " + abbreviate(left) + " -> " + abbreviate(right));
            }
        }
        return entries.size() + " differing (" + firstFew(entries) + ")";
    }

    private static String describeCollection(final Collection<?> before, final Collection<?> after) {
        final List<String> missing = new ArrayList<>();
        for (final Object item : before) {
            if (!after.contains(item)) {
                missing.add(String.valueOf(item));
            }
        }
        final List<String> added = new ArrayList<>();
        for (final Object item : after) {
            if (!before.contains(item)) {
                added.add(String.valueOf(item));
            }
        }
        return before.size() + " -> " + after.size()
            + ", lost " + missing.size() + " (" + firstFew(missing) + ")"
            + ", gained " + added.size() + " (" + firstFew(added) + ")";
    }

    private static String firstFew(final List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        final int shown = Math.min(3, items.size());
        final String joined = String.join(", ", items.subList(0, shown));
        return shown < items.size() ? joined + ", ..." : joined;
    }

    private static String abbreviate(final Object value) {
        if (value == null) {
            return "null";
        }
        final String text = String.valueOf(value);
        // The inventory and persistent data fields are base64 blobs; printing one in full buries
        // every other difference in the output
        return text.length() <= 60 ? text : text.substring(0, 57) + "...";
    }

    private static Object read(final RecordComponent component, final PlayerSnapshot snapshot) {
        try {
            return component.getAccessor().invoke(snapshot);
        } catch (final ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read snapshot component " + component.getName(), exception);
        }
    }
}
