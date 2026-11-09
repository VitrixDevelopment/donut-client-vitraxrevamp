package dev.donut.client.config;

public final class ClientConfig {
    public int schemaVersion = 1;
    public FreecamSettings freecam = new FreecamSettings();
    public AutoBuilderSettings autoBuilder = new AutoBuilderSettings();
    public ChunkFinderSettings chunkFinder = new ChunkFinderSettings();
    public LightDebugSettings lightDebug = new LightDebugSettings();
    public SoundDetectorSettings soundDetector = new SoundDetectorSettings();
    public StaffDetectorSettings staffDetector = new StaffDetectorSettings();
    public BlockEspSettings blockEsp = new BlockEspSettings();
    public AmethystSettings amethyst = new AmethystSettings();
    public FullbrightSettings fullbright = new FullbrightSettings();
    public FlySettings fly = new FlySettings();
    public ProgressBarSettings progressBar = new ProgressBarSettings();
    public NameHiderSettings nameHider = new NameHiderSettings();
    public HotbarSettings hotbar = new HotbarSettings();
    public int accentColor = 0x56DCCB;
    public ClickGuiStyle clickGuiStyle = new ClickGuiStyle();
    public boolean clickGuiParticles = true;
    public boolean showHud = true;


    public void sanitize() {
        if (clickGuiStyle == null) clickGuiStyle = new ClickGuiStyle();
        clickGuiStyle.sanitize();
        if (freecam == null) freecam = new FreecamSettings();
        if (autoBuilder == null) autoBuilder = new AutoBuilderSettings();
        if (chunkFinder == null) chunkFinder = new ChunkFinderSettings();
        if (amethyst == null) amethyst = new AmethystSettings();
        if (fullbright == null) fullbright = new FullbrightSettings();
        if (fly == null) fly = new FlySettings();
        if (progressBar == null) progressBar = new ProgressBarSettings();
        if (nameHider == null) nameHider = new NameHiderSettings();
        if (hotbar == null) hotbar = new HotbarSettings();
        freecam.sanitize();
        autoBuilder.sanitize();
        chunkFinder.sanitize();
        amethyst.sanitize();
        if (lightDebug == null) lightDebug = new LightDebugSettings();
        lightDebug.sanitize();
        if (soundDetector == null) soundDetector = new SoundDetectorSettings();
        soundDetector.sanitize();
        if (staffDetector == null) staffDetector = new StaffDetectorSettings();
        staffDetector.sanitize();
        if (blockEsp == null) blockEsp = new BlockEspSettings();
        blockEsp.sanitize();
        progressBar.sanitize();
        hotbar.sanitize();
        accentColor &= 0xFFFFFF;
    }
}
