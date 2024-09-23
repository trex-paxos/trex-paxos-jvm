package com.github.trex_paxos;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class LoggerConfig {
  private static final Logger LOGGER = Logger.getLogger(LoggerConfig.class.getName());

  static {
    try {
      // Remove all existing handlers
      for (Handler handler : Logger.getLogger("").getHandlers()) {
        Logger.getLogger("").removeHandler(handler);
      }

      // Create and set a new ConsoleHandler
      ConsoleHandler consoleHandler = new ConsoleHandler(){{setOutputStream(System.out);}};

      // Create a custom formatter
      Formatter customFormatter = new Formatter() {
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        @Override
        public String format(LogRecord record) {
          StringBuilder builder = new StringBuilder();
          builder.append(dateFormat.format(new Date(record.getMillis())))
              .append(" [")
              .append(record.getLevel().getName())
              .append("] ")
              .append(formatMessage(record))
              .append("\n");

          if (record.getThrown() != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            record.getThrown().printStackTrace(pw);
            builder.append(sw);
          }

          return builder.toString();
        }
      };

      consoleHandler.setFormatter(customFormatter);

      // Set the handler to the root logger
      Logger.getLogger("").addHandler(consoleHandler);

      // Set the desired log level (adjust as needed)
      Logger.getLogger("").setLevel(Level.INFO);

      LOGGER.info("Logger configuration completed successfully.");
    } catch (Exception e) {
      System.err.println("Failed to configure logger: " + e.getMessage());
      e.printStackTrace();
    }
  }

  public static void initialize() {
    // This method can be called to ensure the static block is executed
  }
}
