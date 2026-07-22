package red.jackf.chesttracker.impl.qmsync;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.jackfredlib.client.api.gps.Coordinate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class QMSyncCommand {
    private QMSyncCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("qmsync")
                        .then(ClientCommandManager.literal("connect")
                                .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> connect(ctx.getSource(), StringArgumentType.getString(ctx, "url")))))
                        .then(ClientCommandManager.literal("stop")
                                .executes(ctx -> stop(ctx.getSource())))
                        .then(ClientCommandManager.literal("status")
                                .executes(ctx -> status(ctx.getSource())))
        ));
    }

    private static int connect(FabricClientCommandSource source, String url) {
        Optional<MemoryBankImpl> bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        Optional<Coordinate> coordinate = Coordinate.getCurrent();
        if (bankOpt.isEmpty() || coordinate.isEmpty()) {
            source.sendError(Component.translatable("chesttracker.qmsync.noBank"));
            return 0;
        }

        URI parsed = QMSyncHttp.parseBaseUrl(url);
        if (parsed == null) {
            source.sendError(Component.translatable("chesttracker.qmsync.invalidUrl", url));
            return 0;
        }

        MemoryBankImpl bank = bankOpt.get();
        final String bankId = bank.getId();
        QMSyncHttp.Identity identity = new QMSyncHttp.Identity(
                source.getPlayer().getUUID(),
                source.getPlayer().getName().getString(),
                coordinate.get().id(),
                coordinate.get().userFriendlyName()
        );

        source.sendFeedback(Component.translatable("chesttracker.qmsync.connecting", parsed.toString())
                                     .withStyle(ChatFormatting.GRAY));

        QMSyncHttp.handshake(parsed.toString(), identity).whenComplete((result, throwable) ->
                source.getClient().execute(() -> {
                    QMSyncHttp.Result outcome = throwable != null ? QMSyncHttp.Result.CONNECTION_FAILED : result;

                    if (outcome == QMSyncHttp.Result.SYNCED) {
                        Optional<MemoryBankImpl> current = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
                        if (current.isEmpty() || !current.get().getId().equals(bankId)) {
                            source.sendError(Component.translatable("chesttracker.qmsync.worldChanged"));
                            return;
                        }
                        QMSyncSettings settings = current.get().getMetadata().getQMSyncSettings();
                        settings.url = parsed.toString();
                        settings.enabled = true;
                        settings.paused = false;
                        QMSyncManager.INSTANCE.markActivated(bankId);
                        MemoryBankAccessImpl.INSTANCE.save();
                        source.sendFeedback(Component.translatable("chesttracker.qmsync.synced")
                                                     .withStyle(ChatFormatting.GREEN));
                    } else {
                        source.sendError(switch (outcome) {
                            case ACCESS_DENIED -> Component.translatable("chesttracker.qmsync.accessDenied");
                            case URL_NOT_FOUND -> Component.translatable("chesttracker.qmsync.urlNotFound", parsed.getHost());
                            case NOT_A_QMSYNC_SERVER -> Component.translatable("chesttracker.qmsync.notAQMSyncServer", parsed.getHost());
                            default -> Component.translatable("chesttracker.qmsync.connectionFailed");
                        });
                    }
                }));
        return 1;
    }

    private static int stop(FabricClientCommandSource source) {
        Optional<MemoryBankImpl> bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (bankOpt.isEmpty() || !bankOpt.get().getMetadata().getQMSyncSettings().isConnected()) {
            source.sendError(Component.translatable("chesttracker.qmsync.notActive"));
            return 0;
        }

        QMSyncSettings settings = bankOpt.get().getMetadata().getQMSyncSettings();
        settings.forget();
        QMSyncManager.INSTANCE.deactivate();
        MemoryBankAccessImpl.INSTANCE.save();
        source.sendFeedback(Component.translatable("chesttracker.qmsync.stopped").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int status(FabricClientCommandSource source) {
        Optional<MemoryBankImpl> bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (bankOpt.isEmpty()) {
            source.sendError(Component.translatable("chesttracker.qmsync.noBank"));
            return 0;
        }

        MemoryBankImpl bank = bankOpt.get();
        QMSyncSettings settings = bank.getMetadata().getQMSyncSettings();

        source.sendFeedback(Component.translatable("chesttracker.qmsync.status.header").withStyle(ChatFormatting.GOLD));

        if (settings.isActive()) {
            source.sendFeedback(Component.translatable("chesttracker.qmsync.status.active", settings.url)
                                         .withStyle(ChatFormatting.GREEN));
        } else if (settings.isConnected()) {
            source.sendFeedback(Component.translatable("chesttracker.qmsync.status.paused", settings.url)
                                         .withStyle(ChatFormatting.YELLOW));
        } else {
            source.sendFeedback(Component.translatable("chesttracker.qmsync.status.inactive")
                                         .withStyle(ChatFormatting.GRAY));
        }

        Coordinate.getCurrent().ifPresent(coordinate ->
                source.sendFeedback(Component.translatable("chesttracker.qmsync.status.server",
                        coordinate.userFriendlyName(), coordinate.id()).withStyle(ChatFormatting.GRAY)));

        source.sendFeedback(Component.translatable("chesttracker.qmsync.status.player",
                source.getPlayer().getName().getString(), source.getPlayer().getUUID().toString())
                                     .withStyle(ChatFormatting.GRAY));

        Optional<Instant> lastSuccess = QMSyncManager.INSTANCE.getLastSuccess();
        if (lastSuccess.isPresent()) {
            long secondsAgo = Duration.between(lastSuccess.get(), Instant.now()).toSeconds();
            source.sendFeedback(Component.translatable("chesttracker.qmsync.status.lastSync",
                    secondsAgo, QMSyncManager.INSTANCE.getLastResult().orElse("unknown"))
                                         .withStyle(ChatFormatting.GREEN));
        } else {
            source.sendFeedback(Component.translatable("chesttracker.qmsync.status.lastSync.never",
                    QMSyncManager.INSTANCE.getLastResult().orElse("-")).withStyle(ChatFormatting.YELLOW));
        }

        int keys = bank.getMemories().size();
        int containers = bank.getMemories().values().stream().mapToInt(key -> key.getMemories().size()).sum();
        int stacks = bank.getMemories().values().stream()
                .flatMap(key -> key.getMemories().values().stream())
                .mapToInt(memory -> memory.items().size())
                .sum();
        source.sendFeedback(Component.translatable("chesttracker.qmsync.status.counts", containers, stacks, keys)
                                     .withStyle(ChatFormatting.GRAY));

        return 1;
    }
}
