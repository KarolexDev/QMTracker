package red.jackf.chesttracker.impl.qmsync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.api.memory.CommonKeys;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.compat.servers.hypixel.HypixelProvider;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.memory.MemoryKeyImpl;
import red.jackf.jackfredlib.client.api.gps.Coordinate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.resources.Identifier;

/**
 * Drives the periodic upload loop: watches the loaded memory bank each tick, announces auto-resume, snapshots and
 * uploads the bank when it changed, and reports failure/recovery transitions in chat exactly once each.
 */
public class QMSyncManager {
    public static final QMSyncManager INSTANCE = new QMSyncManager();
    private static final Logger LOGGER = ChestTracker.getLogger("QMSync");
    private static final Set<Identifier> ENDER_CHEST_KEYS = Set.of(
            CommonKeys.ENDER_CHEST_KEY,
            CommonKeys.SHARE_ENDER_CHEST,
            HypixelProvider.SKYBLOCK_ENDER_CHEST
    );

    /** Bank id the manager has announced/is active for; null when no synced bank is loaded. */
    @Nullable
    private String activeBankId = null;
    @Nullable
    private String lastUploadHash = null;
    private boolean failing = false;
    private long lastAttemptMs = 0;
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);

    // telemetry for /qmsync status
    @Nullable
    private Instant lastSuccess = null;
    @Nullable
    private String lastResult = null;

    private QMSyncManager() {}

    public void setup() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    /**
     * Called by /qmsync connect after a successful handshake so the auto-resume notice doesn't double up with the
     * command's own "SYNCED" feedback.
     */
    public void markActivated(String bankId) {
        resetSession();
        this.activeBankId = bankId;
    }

    public void deactivate() {
        resetSession();
    }

    public Optional<Instant> getLastSuccess() {
        return Optional.ofNullable(lastSuccess);
    }

    public Optional<String> getLastResult() {
        return Optional.ofNullable(lastResult);
    }

    private void resetSession() {
        this.activeBankId = null;
        this.lastUploadHash = null;
        this.failing = false;
        this.lastAttemptMs = 0;
        this.lastSuccess = null;
        this.lastResult = null;
    }

    private void tick(Minecraft client) {
        Optional<MemoryBankImpl> bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (bankOpt.isEmpty() || client.player == null || client.level == null) {
            if (activeBankId != null) resetSession();
            return;
        }

        MemoryBankImpl bank = bankOpt.get();
        QMSyncSettings settings = bank.getMetadata().getQMSyncSettings();
        if (!settings.isActive()) {
            if (activeBankId != null) resetSession();
            return;
        }

        if (!bank.getId().equals(activeBankId)) {
            resetSession();
            this.activeBankId = bank.getId();
            if (settings.chatNotifications == QMSyncSettings.ChatNotifications.ALL)
                sendChat(client, Component.translatable("chesttracker.qmsync.resumed", settings.url), ChatFormatting.GREEN);
        }

        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < settings.intervalSeconds * 1000L) return;
        if (!requestInFlight.compareAndSet(false, true)) return;
        lastAttemptMs = now;

        startSync(client, bank, settings);
    }

    private void startSync(Minecraft client, MemoryBankImpl bank, QMSyncSettings settings) {
        Coordinate coordinate = Coordinate.getCurrent().orElse(null);
        if (coordinate == null) {
            requestInFlight.set(false);
            return;
        }

        // snapshot on the client thread, encode + upload off-thread (same pattern as JsonBackend's async save)
        final String bankId = bank.getId();
        final String url = settings.url;
        final boolean stripNames = !settings.syncContainerNames;
        final Map<Identifier, MemoryKeyImpl> snapshot = new HashMap<>();
        for (Map.Entry<Identifier, MemoryKeyImpl> entry : bank.getMemories().entrySet()) {
            if (!settings.syncEnderChest && ENDER_CHEST_KEYS.contains(entry.getKey())) continue;
            snapshot.put(entry.getKey(), entry.getValue());
        }
        final DynamicOps<JsonElement> ops = client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        final QMSyncHttp.Identity identity = new QMSyncHttp.Identity(
                client.player.getUUID(),
                client.player.getName().getString(),
                coordinate.id(),
                coordinate.userFriendlyName()
        );
        // while failing, bypass the unchanged-check so recovery is actually detected
        final boolean forceUpload = this.failing;
        final String previousHash = this.lastUploadHash;

        CompletableFuture.supplyAsync(() -> {
            Optional<JsonElement> encoded = MemoryBankImpl.DATA_CODEC.encodeStart(ops, snapshot).result();
            if (encoded.isEmpty()) throw new IllegalStateException("Failed to encode memory bank to JSON");
            JsonElement data = encoded.get();
            if (stripNames) stripContainerNames(data);
            String hash = sha256(data.toString());
            if (!forceUpload && hash.equals(previousHash)) {
                return CompletableFuture.completedFuture(new Attempt(QMSyncHttp.Result.SYNCED, hash, true));
            }
            return QMSyncHttp.sync(url, identity, data).thenApply(result -> new Attempt(result, hash, false));
        }, Util.backgroundExecutor()).thenCompose(future -> future).whenComplete((attempt, throwable) ->
                client.execute(() -> {
                    requestInFlight.set(false);
                    if (throwable != null) {
                        LOGGER.error("QMSync upload failed unexpectedly", throwable);
                        handleResult(client, bankId, QMSyncHttp.Result.CONNECTION_FAILED, null, false);
                    } else {
                        handleResult(client, bankId, attempt.result(), attempt.hash(), attempt.skipped());
                    }
                }));
    }

    private record Attempt(QMSyncHttp.Result result, String hash, boolean skipped) {}

    private void handleResult(Minecraft client, String bankId, QMSyncHttp.Result result, @Nullable String hash, boolean skipped) {
        // world/bank changed while the request was in flight; result no longer applies
        if (!bankId.equals(activeBankId)) return;

        boolean notify = MemoryBankAccessImpl.INSTANCE.getLoadedInternal()
                .map(bank -> bank.getMetadata().getQMSyncSettings().chatNotifications != QMSyncSettings.ChatNotifications.SILENT)
                .orElse(true);

        if (result == QMSyncHttp.Result.SYNCED) {
            this.lastUploadHash = hash;
            this.lastSuccess = Instant.now();
            this.lastResult = skipped ? "up to date" : "synced";
            if (failing) {
                this.failing = false;
                if (notify) sendChat(client, Component.translatable("chesttracker.qmsync.reestablished"), ChatFormatting.GREEN);
            }
        } else {
            this.lastResult = switch (result) {
                case ACCESS_DENIED -> "access denied";
                case URL_NOT_FOUND -> "URL not found";
                case NOT_A_QMSYNC_SERVER -> "not a QMSync server";
                default -> "connection failed";
            };
            if (!failing) {
                this.failing = true;
                if (notify) sendChat(client, Component.translatable("chesttracker.qmsync.syncFailed", this.lastResult), ChatFormatting.RED);
            }
        }
    }

    /**
     * Privacy filter: removes user-written container names from an encoded snapshot before upload.
     */
    private static void stripContainerNames(JsonElement data) {
        if (!data.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> keyEntry : data.getAsJsonObject().entrySet()) {
            if (!keyEntry.getValue().isJsonObject()) continue;
            JsonObject memoryKey = keyEntry.getValue().getAsJsonObject();
            if (memoryKey.get("memories") instanceof JsonObject memories)
                for (Map.Entry<String, JsonElement> memory : memories.entrySet())
                    if (memory.getValue().isJsonObject())
                        memory.getValue().getAsJsonObject().remove("name");
            if (memoryKey.get("overrides") instanceof JsonObject overrides)
                for (Map.Entry<String, JsonElement> override : overrides.entrySet())
                    if (override.getValue().isJsonObject())
                        override.getValue().getAsJsonObject().remove("customName");
        }
    }

    private void sendChat(Minecraft client, Component message, ChatFormatting colour) {
        if (client.player == null) return;
        client.player.displayClientMessage(
                Component.literal("[QMSync] ").withStyle(ChatFormatting.GRAY)
                         .append(message.copy().withStyle(colour)), false);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
