package com.github.trex_paxos;

import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

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
