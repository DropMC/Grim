package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.internal.storage.identity.LocalCacheLink;
import ac.grim.grimac.manager.AlertFilter;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.StringParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * DropMC fork: {@code /ac filter} picks whose alerts and verbose a staff member receives, and
 * {@code /ac watch} names players who always get through. See {@link AlertFilter}.
 */
public class GrimFilter implements BuildableCommand {

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(commandManager.commandBuilder("ac")
                .literal("filter", Description.of("Show whose alerts and verbose you receive"))
                .permission("grim.alerts")
                .handler(this::handleShow));
        commandManager.command(commandManager.commandBuilder("ac")
                .literal("filter")
                .literal("all", Description.of("Receive alerts and verbose from every player"))
                .permission("grim.alerts")
                .handler(context -> handleMode(context, AlertFilter.Mode.ALL)));
        commandManager.command(commandManager.commandBuilder("ac")
                .literal("filter")
                .literal("suspects", Description.of("Receive alerts and verbose only from suspects and watched players"))
                .permission("grim.alerts")
                .handler(context -> handleMode(context, AlertFilter.Mode.SUSPECTS)));
        commandManager.command(commandManager.commandBuilder("ac")
                .literal("watch")
                .literal("clear", Description.of("Stop watching every player"))
                .permission("grim.alerts")
                .handler(this::handleClear));
        commandManager.command(commandManager.commandBuilder("ac")
                .literal("watch", Description.of("Add a player to your filter, or remove them"))
                .permission("grim.alerts")
                .required("target", StringParser.stringParser(), arguments.onlinePlayerSuggestions())
                .handler(this::handleWatch));
    }

    private void handleShow(@NotNull CommandContext<Sender> context) {
        PlatformPlayer player = player(context.sender());
        if (player == null) {
            return;
        }
        AlertFilter.Settings settings = AlertFilter.get(player.getUniqueId());
        sendMode(context.sender(), settings.mode());
        if (settings.watched().isEmpty()) {
            send(context.sender(), "filter-watching-none", "%prefix% &fYou are not watching anyone.", null);
        } else {
            send(context.sender(), "filter-watching", "%prefix% &fWatching: &b%player%",
                    String.join(", ", settings.watched().values()));
        }
    }

    private void handleMode(@NotNull CommandContext<Sender> context, @NotNull AlertFilter.Mode mode) {
        PlatformPlayer player = player(context.sender());
        if (player == null) {
            return;
        }
        AlertFilter.setMode(player.getUniqueId(), mode);
        sendMode(context.sender(), mode);
    }

    private void handleClear(@NotNull CommandContext<Sender> context) {
        PlatformPlayer player = player(context.sender());
        if (player == null) {
            return;
        }
        AlertFilter.clearWatch(player.getUniqueId());
        send(context.sender(), "watch-cleared", "%prefix% &fYou are no longer watching anyone.", null);
    }

    private void handleWatch(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlatformPlayer player = player(sender);
        if (player == null) {
            return;
        }
        String name = context.get("target");
        resolve(name).thenAccept(target -> {
            if (target.isEmpty()) {
                send(sender, "watch-unknown-player", "%prefix% &cNo player named %player% has joined.", name);
                return;
            }
            PlatformPlayer online = GrimAPI.INSTANCE.getPlatformPlayerFactory().getFromName(name);
            String shown = online == null ? name : online.getName();
            boolean watching = AlertFilter.toggleWatch(player.getUniqueId(), target.get(), shown);
            if (watching) {
                send(sender, "watch-added", "%prefix% &fYou are now watching &b%player%&f.", shown);
            } else {
                send(sender, "watch-removed", "%prefix% &fYou are no longer watching &b%player%&f.", shown);
            }
        });
    }

    /**
     * The online player of that name first, then anyone who has joined before. Only the local name
     * cache is asked: the resolver chain ends with an offline-mode UUID derived from any name, which
     * would let a typo become a watched player who can never flag.
     */
    private static CompletableFuture<Optional<UUID>> resolve(String name) {
        PlatformPlayer online = GrimAPI.INSTANCE.getPlatformPlayerFactory().getFromName(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(online.getUniqueId()));
        }
        DataStore store = GrimAPI.INSTANCE.getDataStoreLifecycle().dataStore();
        if (store == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return new LocalCacheLink(store).resolveByName(name).toCompletableFuture()
                .exceptionally(error -> Optional.empty());
    }

    private static void sendMode(Sender sender, AlertFilter.Mode mode) {
        if (mode == AlertFilter.Mode.ALL) {
            send(sender, "filter-all", "%prefix% &fYou receive alerts and verbose from every player.", null);
        } else {
            send(sender, "filter-suspects", "%prefix% &fYou receive alerts and verbose only from suspects and watched players.", null);
        }
    }

    private static @Nullable PlatformPlayer player(Sender sender) {
        if (!sender.isPlayer()) {
            send(sender, "run-as-player", "%prefix% &cThis command can only be used by players!", null);
            return null;
        }
        return sender.getPlatformPlayer();
    }

    private static void send(Sender sender, String key, String fallback, @Nullable String player) {
        String message = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse(key, fallback);
        if (player != null) {
            message = message.replace("%player%", player);
        }
        sender.sendMessage(MessageUtil.miniMessage(MessageUtil.replacePlaceholders(sender, message)));
    }
}
