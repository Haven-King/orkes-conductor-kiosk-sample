package io.orkes.kiosk;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.temporal.ChronoUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static java.util.FormatProcessor.FMT;

public class Logging {
    private static long MAX_LOG_NAME_LENGTH = 0;

    public static Logger getLogger(String name) {
        MAX_LOG_NAME_LENGTH = Math.max(MAX_LOG_NAME_LENGTH, name.length());

        final var logger = Logger.getLogger(name);
        final var handler = new ConsoleHandler();

        Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                var error = "";
                var thrown = record.getThrown();

                if (thrown != null) {
                    StringWriter writer = new StringWriter();

                    thrown.printStackTrace(new PrintWriter(writer));

                    error = STR."\n\t\{String.join("\n\t", writer.toString().split("\n"))}";
                }

                var name = String.format("%" + MAX_LOG_NAME_LENGTH + "s", record.getLoggerName());

                return FMT."\{record.getInstant().truncatedTo(ChronoUnit.MILLIS)} [\{record.getLevel()}] \{name} - \{record.getMessage()}\{error}\n";
            }
        };

        handler.setFormatter(formatter);
        logger.addHandler(handler);

        logger.setUseParentHandlers(false);

        return logger;
    }

    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getSimpleName());
    }
}
