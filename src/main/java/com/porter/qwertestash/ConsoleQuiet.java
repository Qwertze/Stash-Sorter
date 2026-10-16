package com.porter.qwertestash;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

/**
 * In quiet log mode the proxy's own "Pathfinder" and "Cache" INFO lines are kept off the terminal, so that the step lines
 * stand out. The log files are not touched, and no logger level is changed: this is one filter on the terminal appender
 * that does nothing unless the log level is quiet.
 */
public final class ConsoleQuiet extends Filter<ILoggingEvent> {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (CONFIG != null && CONFIG.logLevel == StashConfig.LogLevel.Quiet && event.getLevel().toInt() <= Level.INFO_INT) {
            String name = event.getLoggerName();
            if ("Pathfinder".equals(name) || "Cache".equals(name)) return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }

    /**
     * Adds the filter to the terminal appender, once. Whatever goes wrong (another logging setup, another class loader)
     * leaves the terminal as it was and the plugin's own output as the only thing quiet mode controls.
     */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            Appender<ILoggingEvent> terminal = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC_TERMINAL");
            if (terminal == null) {
                QwerteStashPlugin.LOG.debug("Qwerte Stash: no terminal appender found, quiet mode only covers the sorter's own output");
                return;
            }
            ConsoleQuiet filter = new ConsoleQuiet();
            filter.start();
            terminal.addFilter(filter);
        } catch (LinkageError | ClassCastException | IllegalStateException e) {
            QwerteStashPlugin.LOG.debug("Qwerte Stash: couldn't filter the terminal, quiet mode only covers the sorter's own output", e);
        }
    }
}
