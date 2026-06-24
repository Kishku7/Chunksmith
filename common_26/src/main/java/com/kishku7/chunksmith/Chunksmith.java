package com.kishku7.chunksmith;

import com.kishku7.chunksmith.api.ChunksmithAPI;
import com.kishku7.chunksmith.api.ChunksmithAPIImpl;
import com.kishku7.chunksmith.command.CancelCommand;
import com.kishku7.chunksmith.command.CenterCommand;
import com.kishku7.chunksmith.command.ChunksmithCommand;
import com.kishku7.chunksmith.command.CommandLiteral;
import com.kishku7.chunksmith.command.ConfirmCommand;
import com.kishku7.chunksmith.command.ContinueCommand;
import com.kishku7.chunksmith.command.CornersCommand;
import com.kishku7.chunksmith.command.DebugCommand;
import com.kishku7.chunksmith.command.HelpCommand;
import com.kishku7.chunksmith.command.PatternCommand;
import com.kishku7.chunksmith.command.PauseCommand;
import com.kishku7.chunksmith.command.ProgressCommand;
import com.kishku7.chunksmith.command.QuietCommand;
import com.kishku7.chunksmith.command.RadiusCommand;
import com.kishku7.chunksmith.command.ReloadCommand;
import com.kishku7.chunksmith.command.SelectionCommand;
import com.kishku7.chunksmith.command.ShapeCommand;
import com.kishku7.chunksmith.command.SilentCommand;
import com.kishku7.chunksmith.command.SpawnCommand;
import com.kishku7.chunksmith.command.StartCommand;
import com.kishku7.chunksmith.command.TrimCommand;
import com.kishku7.chunksmith.command.WorldBorderCommand;
import com.kishku7.chunksmith.command.WorldCommand;
import com.kishku7.chunksmith.event.EventBus;
import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.Server;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.PendingAction;
import com.kishku7.chunksmith.util.RegionCache;
import com.kishku7.chunksmith.util.TaskLoader;
import com.kishku7.chunksmith.util.TaskScheduler;
import com.kishku7.chunksmith.util.Translator;
import com.kishku7.chunksmith.util.Version;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class Chunksmith {
    private final Server server;
    private final Config config;
    private final TaskLoader taskLoader;
    private final EventBus eventBus;
    private final Selection.Builder selection;
    private final TaskScheduler scheduler = new TaskScheduler();
    private final Map<String, GenerationTask> generationTasks = new ConcurrentHashMap<>();
    private final Map<String, TrimCommand.Task> trimTasks = new ConcurrentHashMap<>();
    private final Map<String, PendingAction> pendingActions = new HashMap<>();
    private final RegionCache regionCache = new RegionCache();
    private final double limit;
    private final Version version;
    private final Map<String, ChunksmithCommand> commands;
    private final ChunksmithAPI api;

    public Chunksmith(final Server server, final Config config) {
        this.server = server;
        this.config = config;
        this.taskLoader = new TaskLoader(this);
        this.eventBus = new EventBus();
        this.selection = Selection.builder(this, server.getWorlds().get(0));
        this.limit = loadLimit().orElse(Double.MAX_VALUE);
        this.version = loadVersion();
        this.commands = loadCommands();
        this.api = new ChunksmithAPIImpl(this);
        ChunksmithProvider.register(this);
    }

    public void disable() {
        taskLoader.saveTasks();
        getGenerationTasks().values().forEach(generationTask -> generationTask.stop(false));
        getScheduler().cancelTasks();
        ChunksmithProvider.unregister();
    }

    private Optional<Double> loadLimit() {
        final Path limitFile = config.getDirectory().resolve(".chunky.properties");
        try (final InputStream input = Files.newInputStream(limitFile)) {
            final Properties properties = new Properties();
            properties.load(input);
            return Input.tryDouble(properties.getProperty("radius-limit"));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Version loadVersion() {
        try (final InputStream input = getClass().getClassLoader().getResourceAsStream("version.properties")) {
            final Properties properties = new Properties();
            properties.load(input);
            return new Version(properties.getProperty("version"));
        } catch (IOException e) {
            return Version.INVALID;
        }
    }

    private Map<String, ChunksmithCommand> loadCommands() {
        final Map<String, ChunksmithCommand> commandMap = new HashMap<>();
        commandMap.put(CommandLiteral.CANCEL, new CancelCommand(this));
        commandMap.put(CommandLiteral.CENTER, new CenterCommand(this));
        commandMap.put(CommandLiteral.CONFIRM, new ConfirmCommand(this));
        commandMap.put(CommandLiteral.CONTINUE, new ContinueCommand(this));
        commandMap.put(CommandLiteral.CORNERS, new CornersCommand(this));
        commandMap.put(CommandLiteral.DEBUG, new DebugCommand(this));
        commandMap.put(CommandLiteral.HELP, new HelpCommand(this));
        commandMap.put(CommandLiteral.PATTERN, new PatternCommand(this));
        commandMap.put(CommandLiteral.PAUSE, new PauseCommand(this));
        commandMap.put(CommandLiteral.PROGRESS, new ProgressCommand(this));
        commandMap.put(CommandLiteral.QUIET, new QuietCommand(this));
        commandMap.put(CommandLiteral.RADIUS, new RadiusCommand(this));
        commandMap.put(CommandLiteral.RELOAD, new ReloadCommand(this));
        commandMap.put(CommandLiteral.SELECTION, new SelectionCommand(this));
        commandMap.put(CommandLiteral.SHAPE, new ShapeCommand(this));
        commandMap.put(CommandLiteral.SILENT, new SilentCommand(this));
        commandMap.put(CommandLiteral.SPAWN, new SpawnCommand(this));
        commandMap.put(CommandLiteral.START, new StartCommand(this));
        commandMap.put(CommandLiteral.TRIM, new TrimCommand(this));
        commandMap.put(CommandLiteral.WORLDBORDER, new WorldBorderCommand(this));
        commandMap.put(CommandLiteral.WORLD, new WorldCommand(this));
        return commandMap;
    }

    public TaskScheduler getScheduler() {
        return scheduler;
    }

    public Server getServer() {
        return server;
    }

    public Config getConfig() {
        return config;
    }

    public TaskLoader getTaskLoader() {
        return taskLoader;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public Map<String, GenerationTask> getGenerationTasks() {
        return generationTasks;
    }

    public Map<String, TrimCommand.Task> getTrimTasks() {
        return trimTasks;
    }

    public Map<String, ChunksmithCommand> getCommands() {
        return commands;
    }

    public Selection.Builder getSelection() {
        return selection;
    }

    public Optional<Runnable> getPendingAction(final Sender sender) {
        pendingActions.values().removeIf(PendingAction::hasExpired);
        final PendingAction pendingAction = pendingActions.remove(sender.getName());
        return Optional.ofNullable(pendingAction).map(PendingAction::getAction);
    }

    public void setPendingAction(final Sender sender, final Runnable action) {
        pendingActions.put(sender.getName(), new PendingAction(action));
    }

    public void setLanguage(final String language) {
        Translator.setLanguage(language);
    }

    public RegionCache getRegionCache() {
        return regionCache;
    }

    public double getLimit() {
        return limit;
    }

    public Version getVersion() {
        return version;
    }

    public ChunksmithAPI getApi() {
        return api;
    }
}
