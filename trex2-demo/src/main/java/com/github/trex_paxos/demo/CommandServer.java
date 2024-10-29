package com.github.trex_paxos.demo;

import com.github.trex_paxos.msg.Command;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CommandServer implements AutoCloseable {
  private static final String HELP = "help";
  private static final String SERVERPORT = "serverport";
  private final ServerSocket serverSocket;
  private final CommandProcessor processor;
  private final ExecutorService virtualThreadExecutor;
  private volatile boolean running = true;
  private static final int BUFFER_SIZE = 8192;
  private static final Logger LOGGER = Logger.getLogger(CommandServer.class.getName());

  public CommandServer(int port, CommandProcessor processor) throws IOException {
    this.serverSocket = new ServerSocket(port);
    this.processor = processor;
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Start the command processor thread
    Thread.startVirtualThread(processor::processCommandsLoop);
  }

  public void start() {
    while (running) {
      try {
        Socket clientSocket = serverSocket.accept();
        virtualThreadExecutor.submit(() -> handleClient(clientSocket));
      } catch (IOException e) {
        if (!running) {
          break;
        }
        LOGGER.log(Level.SEVERE, "Error accepting client connection", e);
      }
    }
  }

  private void handleClient(Socket clientSocket) {
    // Configure socket options
    try {
      clientSocket.setTcpNoDelay(true);
      clientSocket.setKeepAlive(true);

      DataInputStream in = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream(), BUFFER_SIZE));
      DataOutputStream out = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream(), BUFFER_SIZE));

      while (running && !clientSocket.isClosed()) {
        try {
          Command command;
          try {
            // Read data length as a short (2 bytes) as the buffer is limited to 8192 bytes
            int length = in.readShort();
            if (length < 0 || length > BUFFER_SIZE) {
              throw new IOException("Invalid data length: " + length);
            }
            byte[] data = new byte[length];
            in.readFully(data);

            command = new Command(UUIDGenerator.generateUUID().toString(), data);
          } catch (EOFException e1) {
            command = null;
          }
          if (command == null) {
            break; // Client closed connection normally
          }

          try {
            // TODO make the timeout configurable
            Result result = processor.submitCommand(command).get(5, TimeUnit.SECONDS);
            writeResult(out, result);
          } catch (TimeoutException e) {
            // Handle timeout - send error response to client
            writeErrorResponse(out, command.clientMsgUuid(), "Processing timeout");
          } catch (ExecutionException e) {
            // Handle processing error
            writeErrorResponse(out, command.clientMsgUuid(), "Processing error");
          }

          out.flush();
        } catch (EOFException e) {
          break; // Client closed connection
        } catch (IOException e) {
          // Handle connection error
          System.err.println("Error processing client data: " + e.getMessage());
          break;
        }
      }
    } catch (Exception e) {
      System.err.println("Client handler error: " + e.getMessage());
    } finally {
      closeQuietly(clientSocket);
    }
  }

  private void writeResult(DataOutputStream out, Result result) throws IOException {
    out.writeUTF(result.value());
  }

  private void writeErrorResponse(DataOutputStream out, String clientMsgUuid, String message) throws IOException {
    byte[] uuidData = clientMsgUuid.getBytes(StandardCharsets.UTF_8);
    out.writeInt(uuidData.length);
    out.write(uuidData);

    byte[] errorData = message.getBytes(StandardCharsets.UTF_8);
    out.writeInt(errorData.length);
    out.write(errorData);
  }

  private void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException e) {
      // Ignore close errors
    }
  }

  @Override
  public void close() throws IOException {
    running = false;
    serverSocket.close();
    virtualThreadExecutor.shutdown();
  }

  public static void main(String[] args) {
    CommandLineParser parser = new CommandLineParser();
    parser.parse(args);

    // Check for options
    if (parser.hasOption(HELP) || parser.hasOption("h")) {
      printHelp();
      return;
    } else if (!parser.hasOption(SERVERPORT)) {
      System.err.println("Missing required option: --" + SERVERPORT);
      printHelp();
      return;
    }

    try (CommandServer server = new CommandServer(Integer.valueOf(parser.getOption(SERVERPORT)), new CommandProcessor())) {
      server.start();
    } catch (IOException e) {
      System.err.println("Could not start server: " + e.getMessage());
    }
  }

  private static void printHelp() {
    System.out.println("Usage: java " + CommandServer.class.getName() + " [options]");
    System.out.println("  --" + SERVERPORT + "=9999    Server TCP Port to listen on");
    System.out.println("  -h, --help        Show this help message");
  }

  public int getPort() {
    return serverSocket.getLocalPort();
  }
}

// TODO make the timeout configurable
class CommandLineParser {
  private final Map<String, String> options = new HashMap<>();
  private final List<String> remainingArgs = new ArrayList<>();

  public void parse(String[] args) {
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];

      if (arg.startsWith("--")) {
        // Handle long options (--option=value or --option value)
        String option = arg.substring(2);
        if (option.contains("=")) {
          String[] parts = option.split("=", 2);
          options.put(parts[0], parts[1]);
        } else if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
          options.put(option, args[++i]);
        } else {
          options.put(option, "true");
        }
      } else if (arg.startsWith("-")) {
        // Handle short options (-o value or -o)
        String option = arg.substring(1);
        if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
          options.put(option, args[++i]);
        } else {
          options.put(option, "true");
        }
      } else {
        remainingArgs.add(arg);
      }
    }
  }

  public String getOption(String name) {
    return options.get(name);
  }

  public boolean hasOption(String name) {
    return options.containsKey(name);
  }

  public List<String> getRemainingArgs() {
    return remainingArgs;
  }
}
