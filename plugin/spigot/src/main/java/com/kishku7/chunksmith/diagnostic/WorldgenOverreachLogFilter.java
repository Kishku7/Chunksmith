package com.kishku7.chunksmith.diagnostic;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import com.kishku7.chunksmith.util.StructureFaultReporter;
import com.kishku7.chunksmith.util.WorldgenOverreachReporter;

/**
 * Best-effort plugin (Spigot/Paper/Folia) counterpart to the Fabric/NeoForge mixins. There is no
 * Mixin on a Bukkit server, so vanilla worldgen spam is captured the only way available: a Log4j2
 * filter on the root logger that watches for the relevant vanilla ERROR lines, routes each into the
 * matching Chunksmith reporter, and {@code DENY}s the event to suppress the raw spam.
 * <p>
 * Two vanilla faults are handled:
 * <ul>
 *   <li>{@code Detected setBlock in a far chunk} -> {@link WorldgenOverreachReporter} (collapsed
 *       single-line overreach reports in the server log).</li>
 *   <li>{@code Block-attached entity at invalid position} -> {@link StructureFaultReporter} (counted
 *       into the periodic worldgen-fault sub-report file). Best-effort only: the plugin path has no
 *       structure context, so the culprit/chunk cannot be attributed here.</li>
 * </ul>
 * Both lines are logged at {@code ERROR}, so the filter short-circuits anything below {@code WARN}
 * and never touches the text of ordinary logs. Any failure is swallowed: a diagnostic must never
 * interfere with normal logging.
 */
public final class WorldgenOverreachLogFilter extends AbstractFilter {
    private final WorldgenOverreachReporter reporter = WorldgenOverreachReporter.get();

    private WorldgenOverreachLogFilter() {
        super(Result.NEUTRAL, Result.NEUTRAL);
    }

    /**
     * Install the filter on the root logger of the running Log4j2 context and return it (or {@code null}
     * if the environment is not Log4j2-core, e.g. an unusual server). Caller should keep the reference
     * to {@link #uninstall} on disable.
     */
    public static WorldgenOverreachLogFilter install() {
        try {
            final org.apache.logging.log4j.spi.LoggerContext spi = org.apache.logging.log4j.LogManager.getContext(false);
            if (!(spi instanceof final LoggerContext ctx)) {
                return null;
            }
            final WorldgenOverreachLogFilter filter = new WorldgenOverreachLogFilter();
            filter.start();
            final Configuration config = ctx.getConfiguration();
            config.getRootLogger().addFilter(filter);
            ctx.updateLoggers();
            return filter;
        } catch (final Throwable t) {
            return null;
        }
    }

    public void uninstall() {
        try {
            final org.apache.logging.log4j.spi.LoggerContext spi = org.apache.logging.log4j.LogManager.getContext(false);
            if (spi instanceof final LoggerContext ctx) {
                ctx.getConfiguration().getRootLogger().removeFilter(this);
                ctx.updateLoggers();
            }
            this.stop();
        } catch (final Throwable ignored) {
            // best-effort
        }
    }

    private Result evaluate(final Level level, final String message) {
        if (level == null || message == null || !level.isMoreSpecificThan(Level.WARN)) {
            return Result.NEUTRAL;
        }
        if (message.contains(WorldgenOverreachReporter.FAR_CHUNK_MARKER)) {
            try {
                reporter.recordFromMessage(message);
            } catch (final Throwable ignored) {
                // never break logging on account of the diagnostic
            }
            return Result.DENY;
        }
        if (message.contains(StructureFaultReporter.BLOCK_ATTACHED_MARKER)) {
            try {
                StructureFaultReporter.get().recordBlockAttachedBestEffort(message);
            } catch (final Throwable ignored) {
                // never break logging on account of the diagnostic
            }
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(final LogEvent event) {
        if (event == null) {
            return Result.NEUTRAL;
        }
        final Message m = event.getMessage();
        return evaluate(event.getLevel(), m == null ? null : m.getFormattedMessage());
    }

    @Override
    public Result filter(final Logger logger, final Level level, final org.apache.logging.log4j.Marker marker,
                         final Message msg, final Throwable t) {
        return evaluate(level, msg == null ? null : msg.getFormattedMessage());
    }

    @Override
    public Result filter(final Logger logger, final Level level, final org.apache.logging.log4j.Marker marker,
                         final Object msg, final Throwable t) {
        return evaluate(level, msg == null ? null : String.valueOf(msg));
    }

    @Override
    public Result filter(final Logger logger, final Level level, final org.apache.logging.log4j.Marker marker,
                         final String msg, final Object... params) {
        return evaluate(level, msg);
    }
}