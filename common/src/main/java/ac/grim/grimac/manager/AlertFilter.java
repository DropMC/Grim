package ac.grim.grimac.manager;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.category.Categories;
import ac.grim.grimac.api.storage.model.SettingRecord;
import ac.grim.grimac.api.storage.model.SettingScope;
import ac.grim.grimac.api.storage.query.Page;
import ac.grim.grimac.api.storage.query.Queries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * DropMC fork: whose alerts and verbose each staff member receives, set with {@code /ac filter} and
 * {@code /ac watch}.
 *
 * <p>{@link Mode#ALL} (the default) lets everything through. {@link Mode#SUSPECTS} lets through only
 * the players another plugin marks as suspects, plus the ones the staff member watches by name. The
 * suspect list is not Grim's: a plugin hands it over with {@link #setSuspects} by reflection, with
 * JDK types only, so neither side needs the other at compile time. Without one, the suspects mode
 * lets through only the watched players.</p>
 *
 * <p>{@link #receives} runs on whichever thread raised the flag, so it answers from memory: each
 * staff member's filter is loaded from the setting store when they join and kept until they leave.
 * A suspect check that throws counts as "receives", so a bug there can never silence the
 * anticheat.</p>
 */
public final class AlertFilter {

    public enum Mode { ALL, SUSPECTS }

    /** @param watched the watched players' UUIDs, each with the name they had when added */
    public record Settings(@NotNull Mode mode, @NotNull Map<UUID, String> watched) {

        public static final Settings DEFAULT = new Settings(Mode.ALL, Map.of());

        public Settings {
            watched = Collections.unmodifiableMap(new LinkedHashMap<>(watched));
        }
    }

    /** Setting store keys, next to Grim's own {@code alerts}, {@code verbose} and {@code brands}. */
    private static final String KEY_MODE = "alert-filter";
    private static final String KEY_WATCHED = "alert-watch";

    private static final Map<UUID, Settings> SETTINGS = new ConcurrentHashMap<>();
    private static volatile @Nullable Predicate<UUID> suspects;

    private AlertFilter() {
    }

    /** Replaces the suspect list; null removes it, and the suspects mode then passes only watched players. */
    public static void setSuspects(@Nullable Predicate<UUID> newSuspects) {
        suspects = newSuspects;
    }

    public static boolean hasSuspects() {
        return suspects != null;
    }

    static boolean receives(@NotNull UUID viewer, @NotNull UUID flagged) {
        Settings settings = SETTINGS.get(viewer);
        if (settings == null || settings.mode() == Mode.ALL || settings.watched().containsKey(flagged)) {
            return true;
        }
        Predicate<UUID> current = suspects;
        if (current == null) {
            return false;
        }
        try {
            return current.test(flagged);
        } catch (RuntimeException exception) {
            return true;
        }
    }

    public static @NotNull Settings get(@NotNull UUID viewer) {
        return SETTINGS.getOrDefault(viewer, Settings.DEFAULT);
    }

    public static void setMode(@NotNull UUID viewer, @NotNull Mode mode) {
        Settings settings = SETTINGS.compute(viewer, (uuid, old) ->
                new Settings(mode, old == null ? Map.of() : old.watched()));
        persist(viewer, KEY_MODE, settings.mode().name().toLowerCase(Locale.ROOT));
    }

    /** Adds {@code target} to the watched players, or removes them if already there. @return whether they are watched now */
    public static boolean toggleWatch(@NotNull UUID viewer, @NotNull UUID target, @NotNull String name) {
        Settings settings = SETTINGS.compute(viewer, (uuid, old) -> {
            Settings base = old == null ? Settings.DEFAULT : old;
            Map<UUID, String> watched = new LinkedHashMap<>(base.watched());
            if (watched.remove(target) == null) {
                watched.put(target, name);
            }
            return new Settings(base.mode(), watched);
        });
        persist(viewer, KEY_WATCHED, encode(settings.watched()));
        return settings.watched().containsKey(target);
    }

    public static void clearWatch(@NotNull UUID viewer) {
        SETTINGS.compute(viewer, (uuid, old) -> new Settings(old == null ? Mode.ALL : old.mode(), Map.of()));
        persist(viewer, KEY_WATCHED, "");
    }

    /** Reads a staff member's filter off the thread; a change they make before it lands wins. */
    public static void load(@NotNull UUID viewer) {
        DataStore store = GrimAPI.INSTANCE.getDataStoreLifecycle().dataStore();
        if (store == null) {
            return;
        }
        read(store, viewer, KEY_MODE).thenCombine(read(store, viewer, KEY_WATCHED), (mode, watched) ->
                        new Settings(mode == null ? Mode.ALL : decodeMode(mode), watched == null ? Map.of() : decode(watched)))
                .thenAccept(loaded -> SETTINGS.putIfAbsent(viewer, loaded));
    }

    public static void evict(@NotNull UUID viewer) {
        SETTINGS.remove(viewer);
    }

    private static CompletableFuture<@Nullable String> read(DataStore store, UUID viewer, String key) {
        return store.query(Categories.SETTING, new Queries.GetSetting(SettingScope.PLAYER, viewer.toString(), key))
                .toCompletableFuture()
                .handle((page, error) -> error == null ? text(page) : null);
    }

    private static void persist(UUID viewer, String key, String value) {
        DataStore store = GrimAPI.INSTANCE.getDataStoreLifecycle().dataStore();
        if (store == null) {
            return;
        }
        long now = System.currentTimeMillis();
        store.submit(Categories.SETTING, e -> e
                .scope(SettingScope.PLAYER)
                .scopeKey(viewer.toString())
                .key(key)
                .value(value.getBytes(StandardCharsets.UTF_8))
                .updatedEpochMs(now));
    }

    private static @Nullable String text(@Nullable Page<SettingRecord> page) {
        if (page == null || page.items().isEmpty()) {
            return null;
        }
        byte[] value = page.items().get(0).value();
        return value == null || value.length == 0 ? null : new String(value, StandardCharsets.UTF_8);
    }

    private static Mode decodeMode(String value) {
        return "suspects".equalsIgnoreCase(value.trim()) ? Mode.SUSPECTS : Mode.ALL;
    }

    /** One {@code uuid name} pair per line. */
    private static String encode(Map<UUID, String> watched) {
        StringBuilder out = new StringBuilder();
        watched.forEach((uuid, name) -> out.append(uuid).append(' ').append(name).append('\n'));
        return out.toString();
    }

    private static Map<UUID, String> decode(String value) {
        Map<UUID, String> watched = new LinkedHashMap<>();
        for (String line : value.split("\n")) {
            String[] parts = line.trim().split(" ", 2);
            try {
                watched.put(UUID.fromString(parts[0]), parts.length > 1 ? parts[1] : parts[0]);
            } catch (IllegalArgumentException ignored) {
                // a malformed line drops only itself
            }
        }
        return watched;
    }
}
