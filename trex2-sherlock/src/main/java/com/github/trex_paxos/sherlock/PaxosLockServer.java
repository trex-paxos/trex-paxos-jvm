/*
 * Copyright 2024 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos.sherlock;

import com.github.trex_paxos.Command;

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

public class PaxosLockServer implements AutoCloseable {
  private static final String HELP = "help";
  private static final String SERVER_PORT = "server-port";
  private final ServerSocket serverSocket;
  private final CommandProcessor processor;
  private final ExecutorService virtualThreadExecutor;
  private volatile boolean running = true;
  private static final int BUFFER_SIZE = 8192;
  private static final Logger LOGGER = Logger.getLogger(PaxosLockServer.class.getName());

  public PaxosLockServer(int port, CommandProcessor processor) throws IOException {
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
    LOGGER.info("Server has shut down.");
  }

  private void handleClient(Socket clientSocket) {
    try {
      clientSocket.setTcpNoDelay(true);
      clientSocket.setKeepAlive(true);
      try (DataInputStream in = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream(), BUFFER_SIZE)); DataOutputStream out = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream(), BUFFER_SIZE))) {
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
            LOGGER.info("Client EOFException so breaking out of loop.");
            break;
          } catch (Exception e) {
            LOGGER.info("Exception processing client data: " + e.getMessage());
            break;
          }
        }
        if (!clientSocket.isClosed()) {
          LOGGER.info("Client connection is closed so not looping existing.");
        } else if (!running) {
          LOGGER.info("Server is shutting down so Socket handler is exiting.");
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Client handler error: " + e.getMessage(), e);
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
    } else if (!parser.hasOption(SERVER_PORT)) {
      System.err.println("Missing required option: --" + SERVER_PORT);
      printHelp();
      return;
    }

    LoggerConfig.initialize();
    try (PaxosLockServer server = new PaxosLockServer(Integer.parseInt(parser.getOption(SERVER_PORT)), new CommandProcessor())) {
      server.start();
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Could not start server: " + e.getMessage(), e);
    }
  }

  private static void printHelp() {
    System.out.println("Usage: java " + PaxosLockServer.class.getName() + " [options]");
    System.out.println("  --" + SERVER_PORT + "=9999    Server TCP Port to listen on");
    System.out.println("  -h, --help        Show this help message");
  }

  @SuppressWarnings("unused")
  public int getPort() {
    return serverSocket.getLocalPort();
  }

  // TODO make the timeout configurable
  static class CommandLineParser {
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

    @SuppressWarnings("unused")
    public List<String> getRemainingArgs() {
      return remainingArgs;
    }
  }
}

