package com.kishku7.chunksmith;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import com.kishku7.chunksmith.api.ChunksmithAPI;
import com.kishku7.chunksmith.command.ChunksmithCommand;
import com.kishku7.chunksmith.command.CommandArguments;
import com.kishku7.chunksmith.command.CommandLiteral;
import com.kishku7.chunksmith.diagnostic.WorldgenOverreachLogFilter;
import com.kishku7.chunksmith.integration.WorldBorderIntegration;
import com.kishku7.chunksmith.platform.BukkitConfig;
import com.kishku7.chunksmith.platform.BukkitPlayer;
import com.kishku7.chunksmith.platform.BukkitSender;
import com.kishku7.chunksmith.platform.BukkitServer;
import com.kishku7.chunksmith.platform.Folia;
import com.kishku7.chunksmith.platform.Paper;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;
import com.kishku7.chunksmith.util.Version;
import com.kishku7.chunksmith.util.StructureFaultReporter;
import com.kishku7.chunksmith.util.WorldgenOverreachReporter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static com.kishku7.chunksmith.util.Translator.translate;

public final class ChunksmithBukkit extends JavaPlugin implements Listener {
    private static final String COMMAND_PERMISSION_KEY = "chunksmith.command.";
    private static final String LEGACY_COMMAND_PERMISSION_KEY = "chunksmith.command.";
    private Chunksmith chunky;
    private WorldgenOverreachLogFilter overreachFilter;

    @Override
    public void onEnable() {
        final Plugin existingChunky = getServer().getPluginManager().getPlugin("Chunksmith");
        if (existingChunky != null && existingChunky != this) {
            getLogger().warning("Chunksmith supersedes Chunksmith. Disabling the Chunksmith plugin - please remove its jar from the plugins folder.");
            getServer().getPluginManager().disablePlugin(existingChunky);
        }
        final File legacyData = new File(getDataFolder().getParentFile(), "Chunksmith");
        if (!getDataFolder().exists() && legacyData.isDirectory()) {
            if (legacyData.renameTo(getDataFolder())) {
                getLogger().info("Migrated existing Chunksmith data folder to Chunksmith.");
            } else {
                getLogger().warning("Could not migrate the Chunksmith data folder to Chunksmith.");
            }
        }
        this.chunky = new Chunksmith(new BukkitServer(this), new BukkitConfig(this));
        final Version currentVersion = new Version(Bukkit.getBukkitVersion(), true);
        if (currentVersion.isValid() && Version.MINECRAFT_1_13_2.isHigherThan(currentVersion)) {
            getLogger().severe(() -> translate(TranslationKey.ERROR_VERSION));
            getServer().getPluginManager().disablePlugin(this);
        }
        if (!isEnabled()) {
            return;
        }
        getServer().getServicesManager().register(Chunksmith.class, chunky, this, ServicePriority.Normal);
        getServer().getServicesManager().register(ChunksmithAPI.class, chunky.getApi(), this, ServicePriority.Normal);
        if (chunky.getConfig().getContinueOnRestart()) {
            final Runnable continueTask = () -> chunky.getCommands().get(CommandLiteral.CONTINUE).execute(chunky.getServer().getConsole(), CommandArguments.empty());
            if (Folia.isFolia()) {
                Folia.onServerInit(this, continueTask);
            } else {
                getServer().getScheduler().scheduleSyncDelayedTask(this, continueTask);
            }
        }
        if (getServer().getPluginManager().getPlugin("WorldBorder") != null) {
            chunky.getServer().getIntegrations().put("border", new WorldBorderIntegration());
        }
        final Metrics metrics = new Metrics(this, 8211);
        metrics.addCustomChart(new SimplePie("language", () -> chunky.getConfig().getLanguage()));
        getServer().getPluginManager().registerEvents(this, this);
        if (!Paper.isPaper()) {
            disablePauseWhenEmptySeconds();
        }
        installOverreachDiagnostic();
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((Plugin) this);
        if (overreachFilter != null) {
            overreachFilter.uninstall();
            overreachFilter = null;
        }
        if (chunky != null) {
            chunky.disable();
        }
    }

    /**
     * Install the best-effort worldgen-overreach diagnostic. There is no Mixin on a Bukkit server, so
     * a Log4j2 filter captures + suppresses vanilla's "Detected setBlock in a far chunk" spam and routes
     * it to {@link WorldgenOverreachReporter}, which collapses each burst into a single aggregated line.
     * A once-per-second scheduler tick drives the reporter's debounce/rollup/end-of-run summary. Both the
     * filter and the tick fail soft - the diagnostic never interferes with generation or normal logging.
     */
    private void installOverreachDiagnostic() {
        StructureFaultReporter.get().setReportFile(getDataFolder().toPath().resolve("worldgen-faults.txt"));
        this.overreachFilter = WorldgenOverreachLogFilter.install();
        if (this.overreachFilter == null) {
            getLogger().info("Worldgen overreach diagnostic unavailable here (non-Log4j2 logging); continuing without it.");
        }
        final Runnable tick = () -> {
            final boolean wgRunning = chunky != null && !chunky.getGenerationTasks().isEmpty();
            WorldgenOverreachReporter.get().tick(wgRunning);
            StructureFaultReporter.get().tick(wgRunning);
        };
        if (Folia.isFolia()) {
            Folia.scheduleFixedGlobal(this, tick, 20L, 20L);
        } else {
            getServer().getScheduler().runTaskTimer(this, tick, 20L, 20L);
        }
    }

    // Canonical permission namespace is chunksmith.command.*; the pre-rename chunky.command.* nodes
    // are still honoured so existing server permission setups keep working after the rename.
    private static boolean hasCommandPermission(final CommandSender sender, final String name) {
        return sender.hasPermission(COMMAND_PERMISSION_KEY + name)
                || sender.hasPermission(LEGACY_COMMAND_PERMISSION_KEY + name);
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        final Sender bukkitSender = sender instanceof final Player player ? new BukkitPlayer(player) : new BukkitSender(sender);
        if (CommandLiteral.CHUNKY.equalsIgnoreCase(label) || CommandLiteral.CY.equalsIgnoreCase(label)) {
            bukkitSender.sendMessagePrefixed(TranslationKey.COMMAND_DEPRECATED_ALIAS);
        }
        final Map<String, ChunksmithCommand> commands = chunky.getCommands();
        final CommandArguments arguments = CommandArguments.of(Arrays.copyOfRange(args, Math.min(1, args.length), args.length));
        if (args.length > 0 && commands.containsKey(args[0].toLowerCase())) {
            if (hasCommandPermission(sender, args[0].toLowerCase())) {
                commands.get(args[0].toLowerCase()).execute(bukkitSender, arguments);
            } else {
                bukkitSender.sendMessage(TranslationKey.COMMAND_NO_PERMISSION);
            }
        } else {
            commands.get(CommandLiteral.HELP).execute(bukkitSender, arguments);
        }
        return true;
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
        if (args.length < 1) {
            return List.of();
        }
        final List<String> suggestions = new ArrayList<>();
        final Map<String, ChunksmithCommand> commands = chunky.getCommands();
        if (args.length == 1) {
            commands.keySet().stream().filter(name -> hasCommandPermission(sender, name)).forEach(suggestions::add);
        } else if (commands.containsKey(args[0].toLowerCase()) && hasCommandPermission(sender, args[0].toLowerCase())) {
            final CommandArguments arguments = CommandArguments.of(Arrays.copyOfRange(args, 1, args.length));
            suggestions.addAll(commands.get(args[0].toLowerCase()).suggestions(arguments));
        }
        return suggestions.stream()
                .filter(s -> s.toLowerCase().contains(args[args.length - 1].toLowerCase()))
                .toList();
    }

    public Chunksmith getChunky() {
        return chunky;
    }

    @EventHandler
    public void onWorldInit(final WorldInitEvent event) {
        chunky.getRegionCache().clear(event.getWorld().getName());
    }

    private void disablePauseWhenEmptySeconds() {
        final Path serverPropertiesPath = Path.of(".").resolve("server.properties");
        final File serverPropertiesFile = serverPropertiesPath.toFile();
        final Properties serverProperties = new Properties();
        try (final FileInputStream serverPropertiesFileInputStream = new FileInputStream(serverPropertiesFile)) {
            serverProperties.load(serverPropertiesFileInputStream);
            final Optional<Integer> pauseWhenEmptySeconds = Input.tryInteger(serverProperties.getProperty("pause-when-empty-seconds"));
            if (pauseWhenEmptySeconds.isPresent() && pauseWhenEmptySeconds.get() > 0) {
                serverProperties.setProperty("pause-when-empty-seconds", "0");
                try (final FileOutputStream serverPropertiesFileOutputStream = new FileOutputStream(serverPropertiesFile)) {
                    serverProperties.store(serverPropertiesFileOutputStream, "Minecraft server properties");
                    getLogger().warning(() -> translate(TranslationKey.ERROR_PAUSE_WHEN_EMPTY_SECONDS));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }
}