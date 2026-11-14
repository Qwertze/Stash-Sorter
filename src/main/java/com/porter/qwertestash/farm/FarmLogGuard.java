package com.porter.qwertestash.farm;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import com.porter.qwerte.core.farm.PositionGuard;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * P4 and D8: a logback filter on the terminal, file and debug-file appenders that denies every position-bearing line the proxy
 * writes, while the farmer role is on. It decides by source (logger name) and message prefix first, because the position
 * pattern does not match decimal coordinates, then by the position pattern and a decimal-triple pattern as a second layer.
 * It only ever drops lines; it never logs, and it holds no position. Any thread.
 */
public final class FarmLogGuard extends Filter<ILoggingEvent> {
    /** Integer triples and x= y= z= (the plugin's ActivityFeed.POSITION, shared through qwerte-core). */
    private static final Pattern POSITION = PositionGuard.POSITION;
    /** One number: integer, decimal or E-notation, optionally with a d or f suffix (1.5, -3.2d, 1.5E3, 6.4e+1). */
    private static final String NUM = "-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[dDfF]?";
    /** Three numbers in a row joined by commas or semicolons (any mix of integer, decimal and E-notation): 1.5,64.0,-3.2. */
    private static final Pattern DECIMAL_TRIPLE = Pattern.compile("(?<![\\w.])" + NUM + "\\s*[,;]\\s*" + NUM + "\\s*[,;]\\s*" + NUM + "(?![\\w.])");
    /** Named coordinates the proxy logs: (x=1.5, y=.., z=..). */
    private static final Pattern NAMED = Pattern.compile("(?i)\\bx\\s*=\\s*-?\\d.*\\bz\\s*=\\s*-?\\d");

    /** A source and a message prefix the proxy is known to put a position behind (the spike's full list). */
    private record Source(String logger, String prefix) {
    }

    // Logger names and call sites checked against the AquariusProxy source (src/main/java/com/aquarius/...), 2026-11:
    // Globals.java:58 CLIENT_LOG="Client", :60 MODULE_LOG="Module", :61 SERVER_LOG="Server", :66 PATH_LOG="Pathfinder".
    // A module's info/warn/debug (ModuleUtils.java:56-89) goes to MODULE_LOG with the prefix "[<ClassName>] "; Bot is a ModuleUtils.
    private static final List<Source> SOURCES = List.of(
        // INFO: every setback (feature/player/Bot.java:738, CLIENT_LOG.info), and the pathfinder's goal (feature/pathfinder/behavior/PathingBehavior.java:130, PATH_LOG.info)
        new Source("Client", "Server teleport"),
        new Source("Pathfinder", "All done. At"),
        // WARN: VisualRange (module/impl/VisualRange.java:50, :91, :123, via ModuleUtils.warn: "Module", prefix "[VisualRange] ")
        new Source("Module", "[VisualRange]"),
        // CoordObfuscation (module/impl/CoordObfuscation.java:307 info, :343 debug: "Module", prefix "[CoordObfuscation] ")
        new Source("Module", "[CoordObfuscation]"),
        // DEBUG (only with debugLogs on, which the farmer refuses): Bot.debug is ModuleUtils.debug, so "Module" with the prefix "[Bot] "
        // (feature/player/Bot.java:289 "Click attacking entity", :302 and :307 "Right click")
        new Source("Module", "[Bot] Click attacking entity"),
        new Source("Module", "[Bot] Right click"),
        // cache/DataCache.java:81 SERVER_LOG.debug(msg) with PlayerCache.getSendingMessage (cache/data/PlayerCache.java:154): "Server", no prefix
        new Source("Server", "Sending player position"),
        // network/client/handler/incoming/level/BlockEntityDataHandler.java:16 CLIENT_LOG.debug
        new Source("Client", "Received ServerUpdateTileEntityPacket")
    );

    /** Pieces of debug lines of the bot and the interaction manager ("[millis] [x, y, z] StartDestroyBlock ..."), matched anywhere. */
    private static final List<String> CONTAINS = List.of("StartDestroyBlock", "ContinueDestroyBlock", "StopDestroyBlock", "Attack Entity",
        "visual range [", "Generated random pos", "Offset for ");

    private final BooleanSupplier active;

    public FarmLogGuard(BooleanSupplier active) {
        this.active = active;
    }

    /** True when the line must be dropped. {@code message} is the formatted message without the logger or level. */
    public static boolean denies(String loggerName, String message) {
        if (message == null || message.isEmpty()) return false;
        String logger = loggerName == null ? "" : loggerName;
        String m = message.startsWith("[Client] ") ? message.substring("[Client] ".length()) : message;
        for (Source s : SOURCES) {
            boolean sameSource = s.logger().equals(logger) || logger.isEmpty();
            if (!sameSource) continue;
            if (m.startsWith(s.prefix())) return true;
            if (s.prefix().startsWith("[") && m.contains(s.prefix())) return true;
        }
        for (String c : CONTAINS) if (m.contains(c)) return true;
        return POSITION.matcher(m).find() || DECIMAL_TRIPLE.matcher(m).find() || NAMED.matcher(m).find();
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        try {
            if (!active.getAsBoolean()) return FilterReply.NEUTRAL;
            if (denies(event.getLoggerName(), event.getFormattedMessage())) return FilterReply.DENY;
            // The throwable's message and every cause's can carry a position too
            var t = event.getThrowableProxy();
            for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
                if (t.getMessage() != null && denies(event.getLoggerName(), t.getMessage())) return FilterReply.DENY;
            }
            return FilterReply.NEUTRAL;
        } catch (RuntimeException | LinkageError e) {
            // Fail closed: a line the filter cannot read is not written while the farmer is on
            return FilterReply.DENY;
        }
    }

    public static final List<String> APPENDERS = List.of("ASYNC_TERMINAL", "ASYNC_FILE", "ASYNC_DEBUG_FILE");
    private static volatile List<String> lastMissing = APPENDERS;

    /**
     * Adds the filter to the proxy's terminal, file and debug-file appenders. Idempotent: an appender that has one already is left
     * alone, so it may be called at every session start. Returns the names of the appenders it could not find, every time (the farmer
     * refuses to start with {@code unsafe_logging} while one is missing).
     */
    public static List<String> install(BooleanSupplier active) {
        try {
            lastMissing = installOn((LoggerContext) LoggerFactory.getILoggerFactory(), active);
        } catch (LinkageError | ClassCastException | IllegalStateException e) {
            lastMissing = APPENDERS;
        }
        return lastMissing;
    }

    /** The missing list of the last {@link #install}. */
    public static List<String> missing() {
        return lastMissing;
    }

    /** As {@link #install} on a given context (tests). */
    public static List<String> installOn(LoggerContext context, BooleanSupplier active) {
        List<String> missing = new ArrayList<>();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        for (String name : APPENDERS) {
            Appender<ILoggingEvent> a = root.getAppender(name);
            if (a == null) {
                missing.add(name);
                continue;
            }
            if (hasGuard(a)) continue;
            FarmLogGuard f = new FarmLogGuard(active);
            f.setContext(context);
            f.start();
            a.addFilter(f);
        }
        return missing;
    }

    private static boolean hasGuard(Appender<ILoggingEvent> a) {
        for (Filter<ILoggingEvent> f : a.getCopyOfAttachedFiltersList()) if (f instanceof FarmLogGuard) return true;
        return false;
    }

    /** True when a FarmLogGuard is attached to each of the three appenders right now (a logback reconfigure drops them). */
    public static boolean attachedEverywhere(LoggerContext context) {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        for (String name : APPENDERS) {
            Appender<ILoggingEvent> a = root.getAppender(name);
            if (a == null || !hasGuard(a)) return false;
        }
        return true;
    }

    /** {@link #attachedEverywhere} on the proxy's own logging context; false when it cannot be read. */
    public static boolean attachedEverywhere() {
        try {
            return attachedEverywhere((LoggerContext) LoggerFactory.getILoggerFactory());
        } catch (LinkageError | ClassCastException | IllegalStateException e) {
            return false;
        }
    }
}
