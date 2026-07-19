package red.jackf.chesttracker.impl.qmsync;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import red.jackf.jackfredlib.api.base.codecs.JFLCodecs;

import java.util.List;
import java.util.Optional;

/**
 * Per-memory-bank QMSync state. Deliberately not copied by {@link red.jackf.chesttracker.impl.memory.metadata.Metadata#fromDefaults}
 * or {@code copyWithSettingsFrom} — a sync target belongs to exactly one server/world and must never leak to another bank.
 */
public class QMSyncSettings {
    public static final int DEFAULT_INTERVAL_SECONDS = 5;
    public static final List<Integer> INTERVAL_SECONDS_OPTIONS = List.of(2, 3, 4, 5, 10, 15, 20, 30, 45, 60);

    public static final Codec<QMSyncSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("url").forGetter(settings -> Optional.ofNullable(settings.url)),
            Codec.BOOL.optionalFieldOf("enabled", false).forGetter(settings -> settings.enabled),
            Codec.BOOL.optionalFieldOf("paused", false).forGetter(settings -> settings.paused),
            Codec.INT.optionalFieldOf("intervalSeconds", DEFAULT_INTERVAL_SECONDS).forGetter(settings -> settings.intervalSeconds),
            Codec.BOOL.optionalFieldOf("syncEnderChest", true).forGetter(settings -> settings.syncEnderChest),
            Codec.BOOL.optionalFieldOf("syncContainerNames", true).forGetter(settings -> settings.syncContainerNames),
            JFLCodecs.forEnum(ChatNotifications.class).optionalFieldOf("chatNotifications", ChatNotifications.ALL)
                    .forGetter(settings -> settings.chatNotifications)
    ).apply(instance, (url, enabled, paused, interval, enderChest, names, notifications) -> {
        QMSyncSettings settings = new QMSyncSettings(url.orElse(null), enabled);
        settings.paused = paused;
        settings.intervalSeconds = interval;
        settings.syncEnderChest = enderChest;
        settings.syncContainerNames = names;
        settings.chatNotifications = notifications;
        return settings;
    }));

    @Nullable
    public String url;
    public boolean enabled;
    public boolean paused = false;
    public int intervalSeconds = DEFAULT_INTERVAL_SECONDS;
    public boolean syncEnderChest = true;
    public boolean syncContainerNames = true;
    public ChatNotifications chatNotifications = ChatNotifications.ALL;

    public QMSyncSettings() {
        this(null, false);
    }

    public QMSyncSettings(@Nullable String url, boolean enabled) {
        this.url = url;
        this.enabled = enabled;
    }

    /**
     * @return whether the periodic upload loop should currently run for this bank.
     */
    public boolean isActive() {
        return this.enabled && this.url != null && !this.paused;
    }

    /**
     * @return whether a connection exists at all, active or paused.
     */
    public boolean isConnected() {
        return this.enabled && this.url != null;
    }

    public void forget() {
        this.url = null;
        this.enabled = false;
        this.paused = false;
    }

    public QMSyncSettings copy() {
        QMSyncSettings copy = new QMSyncSettings(this.url, this.enabled);
        copy.paused = this.paused;
        copy.intervalSeconds = this.intervalSeconds;
        copy.syncEnderChest = this.syncEnderChest;
        copy.syncContainerNames = this.syncContainerNames;
        copy.chatNotifications = this.chatNotifications;
        return copy;
    }

    public enum ChatNotifications {
        ALL(Component.translatable("chesttracker.qmsync.notifications.all")),
        ERRORS_ONLY(Component.translatable("chesttracker.qmsync.notifications.errorsOnly")),
        SILENT(Component.translatable("chesttracker.qmsync.notifications.silent"));

        public final Component label;

        ChatNotifications(Component label) {
            this.label = label;
        }
    }
}
