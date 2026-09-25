package ac.grim.grimac.manager;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * DropMC fork hook: lets another plugin decide, per staff member, whose alerts they receive.
 *
 * <p>The filter is asked {@code (viewer, flagged)} for every player who has alerts enabled, each
 * time an alert about {@code flagged} is sent; answering false skips that viewer for that alert.
 * The console, verbose and brand messages are never filtered. Only JDK types cross the boundary, so
 * the plugin that sets it needs no compile-time dependency on Grim: it sets the filter by reflection
 * through {@link #set}.</p>
 *
 * <p>It runs on whichever thread raised the alert, so it must answer from memory and never block.
 * A filter that throws is treated as "receives", so a bug there can never silence the anticheat.</p>
 */
public final class AlertRecipientFilter {

    private static volatile @Nullable BiPredicate<UUID, UUID> filter;

    private AlertRecipientFilter() {
    }

    /** Replaces the filter; null restores the default, where everyone with alerts on receives all. */
    public static void set(@Nullable BiPredicate<UUID, UUID> newFilter) {
        filter = newFilter;
    }

    static boolean receives(@NotNull UUID viewer, @NotNull UUID flagged) {
        BiPredicate<UUID, UUID> current = filter;
        if (current == null) {
            return true;
        }
        try {
            return current.test(viewer, flagged);
        } catch (RuntimeException exception) {
            return true;
        }
    }
}
