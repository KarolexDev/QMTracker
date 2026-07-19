package red.jackf.chesttracker.impl.memory.metadata;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.impl.qmsync.QMSyncSettings;

import java.time.Instant;
import java.util.Optional;

public class Metadata {
    public static final Codec<Metadata> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.optionalFieldOf("name").forGetter(meta -> Optional.ofNullable(meta.name)),
                    ExtraCodecs.INSTANT_ISO8601.optionalFieldOf("lastModified").forGetter(meta -> Optional.of(meta.lastModified)),
                    Codec.LONG.fieldOf("loadedTime").forGetter(meta -> meta.loadedTime),
                    Codec.BOOL.optionalFieldOf("usesGlobalDefaults").forGetter(meta -> Optional.of(meta.usesGlobalDefaults)),
                    CompatibilitySettings.CODEC.optionalFieldOf("compatibility")
                            .forGetter(meta -> Optional.of(meta.compatibilitySettings)),
                    FilteringSettings.CODEC.optionalFieldOf("filtering")
                            .forGetter(meta -> Optional.of(meta.filteringSettings)),
                    IntegritySettings.CODEC.optionalFieldOf("integrity")
                            .forGetter(meta -> Optional.of(meta.integritySettings)),
                    SearchSettings.CODEC.optionalFieldOf("search")
                            .forGetter(meta -> Optional.of(meta.searchSettings)),
                    VisualSettings.CODEC.optionalFieldOf("visual")
                            .forGetter(meta -> Optional.of(meta.visualSettings)),
                    QMSyncSettings.CODEC.optionalFieldOf("qmsync")
                            .forGetter(meta -> Optional.of(meta.qmSyncSettings))
            ).apply(instance, (name, lastModified, loadedTime, usesGlobalDefaults, compatibility, filtering, integrity, search, visual, qmsync) -> new Metadata(
                    name.orElse(null),
                    lastModified.orElse(Instant.now()),
                    loadedTime,
                    usesGlobalDefaults.orElse(false),
                    compatibility.orElseGet(CompatibilitySettings::new),
                    filtering.orElseGet(FilteringSettings::new),
                    integrity.orElseGet(IntegritySettings::new),
                    search.orElseGet(SearchSettings::new),
                    visual.orElseGet(VisualSettings::new),
                    qmsync.orElseGet(QMSyncSettings::new)
            ))
    );

    @Nullable
    private String name;
    private Instant lastModified;
    private long loadedTime;
    private boolean usesGlobalDefaults;
    private final CompatibilitySettings compatibilitySettings;
    private final FilteringSettings filteringSettings;
    private final IntegritySettings integritySettings;
    private final SearchSettings searchSettings;
    private final VisualSettings visualSettings;
    private final QMSyncSettings qmSyncSettings;

    public Metadata(
            @Nullable String name,
            Instant lastModified,
            long loadedTime,
            boolean usesGlobalDefaults, // flag for Global Settings, old MemoryBankSettings of mod version <=2.8.2 don't mark of default
            CompatibilitySettings compatibilitySettings,
            FilteringSettings filteringSettings,
            IntegritySettings integritySettings,
            SearchSettings searchSettings,
            VisualSettings visualSettings,
            QMSyncSettings qmSyncSettings) {
        this.name = name;
        this.lastModified = lastModified;
        this.loadedTime = loadedTime;
        this.usesGlobalDefaults = usesGlobalDefaults;
        this.compatibilitySettings = compatibilitySettings;
        this.filteringSettings = filteringSettings;
        this.integritySettings = integritySettings;
        this.searchSettings = searchSettings;
        this.visualSettings = visualSettings;
        this.qmSyncSettings = qmSyncSettings;
    }

    public static Metadata blank() {
        return new Metadata(
                null,
                Instant.now(),
                0L,
                false,
                new CompatibilitySettings(),
                new FilteringSettings(),
                new IntegritySettings(),
                new SearchSettings(),
                new VisualSettings(),
                new QMSyncSettings()
        );
    }

    public static Metadata blankWithName(String name) {
        var blank = blank();
        blank.setName(name);
        return blank;
    }

    public static Metadata fromDefaults(@Nullable String name, Metadata defaults) {
        return fromDefaults(name, defaults, true);
    }

    public static Metadata fromDefaults(@Nullable String name, Metadata defaults, boolean usesGlobalDefaults) {
        return new Metadata(
                name,
                Instant.now(),
                0L,
                usesGlobalDefaults,
                defaults.compatibilitySettings.copy(),
                defaults.filteringSettings.copy(),
                defaults.integritySettings.copy(),
                defaults.searchSettings.copy(),
                defaults.visualSettings.copy(),
                // sync targets are bound to one server/world; never inherit them from defaults
                new QMSyncSettings());
    }

    public Metadata copyAsDefaults() {
        return fromDefaults(null, this, false);
    }

    public Metadata copyWithSettingsFrom(Metadata settingsSource) {
        return new Metadata(
                this.name,
                this.lastModified,
                this.loadedTime,
                this.usesGlobalDefaults,
                settingsSource.compatibilitySettings.copy(),
                settingsSource.filteringSettings.copy(),
                settingsSource.integritySettings.copy(),
                settingsSource.searchSettings.copy(),
                settingsSource.visualSettings.copy(),
                // sync state stays with this bank rather than following transferable settings
                this.qmSyncSettings.copy());
    }

    public boolean usesGlobalDefaults() {
        return usesGlobalDefaults;
    }

    public void setUsesGlobalDefaults(boolean usesGlobalDefaults) {
        this.usesGlobalDefaults = usesGlobalDefaults;
    }

    @Nullable
    public String getName() {
        return name;
    }

    public void setName(@Nullable String name) {
        this.name = name;
    }

    public void updateModified() {
        this.lastModified = Instant.now();
    }

    public long getLoadedTime() {
        return loadedTime;
    }

    public CompatibilitySettings getCompatibilitySettings() {
        return compatibilitySettings;
    }

    public FilteringSettings getFilteringSettings() {
        return filteringSettings;
    }

    public IntegritySettings getIntegritySettings() {
        return integritySettings;
    }

    public SearchSettings getSearchSettings() {
        return searchSettings;
    }

    public VisualSettings getVisualSettings() {
        return visualSettings;
    }

    public QMSyncSettings getQMSyncSettings() {
        return qmSyncSettings;
    }

    public Metadata deepCopy() {
        return new Metadata(name,
                lastModified,
                loadedTime,
                usesGlobalDefaults,
                compatibilitySettings.copy(),
                filteringSettings.copy(),
                integritySettings.copy(),
                searchSettings.copy(),
                visualSettings.copy(),
                qmSyncSettings.copy());
    }

    public void incrementLoadedTime() {
        this.loadedTime++;
    }
}
