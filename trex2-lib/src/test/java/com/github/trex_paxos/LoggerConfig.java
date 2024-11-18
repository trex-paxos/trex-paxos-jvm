package com.github.trex_paxos;

import java.util.Optional;
import java.util.logging.*;

public class LoggerConfig {
  
  static {
    try {
      Logger rootLogger = Logger.getLogger("");

      // Remove existing handlers
      for (Handler handler : rootLogger.getHandlers()) {
        rootLogger.removeHandler(handler);
      }

      // Create and set a new ConsoleHandler
      ConsoleHandler consoleHandler = new ConsoleHandler(){{setOutputStream(System.out);}};

      // Get level from environment or default to FINE
      final var levelString = Optional.ofNullable(System.getenv("LOG_LEVEL"))
          .orElse("FINE");
      Level level = Level.parse(levelString);

      // Set level for both handler and logger
      consoleHandler.setLevel(level);
      rootLogger.setLevel(level);

      // Add handler to root logger
      rootLogger.addHandler(consoleHandler);

      // Set formatter for handler
      consoleHandler.setFormatter(new SimpleFormatter() {
        @Override
        public String format(LogRecord record) {
          return String.format("[%s] %s%n",
              record.getLevel().getName(),
              record.getMessage());
        }
      });

    } catch (Exception e) {
      System.err.println("Failed to configure logger: " + e.getMessage());
      e.printStackTrace();
    }
  }

  public static void initialize() {
    // Method to trigger static initialization
  }
}
