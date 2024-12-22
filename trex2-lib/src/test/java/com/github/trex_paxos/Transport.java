package com.github.trex_paxos;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Transport-agnostic network interface for Paxos message exchange.
 * Implementations must handle connection lifecycle and message delivery.
 */
public interface Transport extends AutoCloseable {

  /**
   * Start the transport, establishing necessary connections and resources.
   * This method must be called before any messages can be sent or received.
   *
   * @throws IOException if the transport fails to start
   */
  void start() throws IOException;

  /**
   * Send a message to a specific node in the cluster.
   *
   * @param nodeId the destination node identifier
   * @param message the message bytes to send
   * @return future completing when the message is sent
   */
  CompletableFuture<Void> send(byte nodeId, byte[] message);

  /**
   * Register a callback for receiving messages.
   * The callback will be invoked for each received message.
   *
   * @param messageHandler callback to process received messages
   */
  void receive(Consumer<Message> messageHandler);

  /**
   * Stop the transport and release resources.
   * No messages can be sent or received after closing.
   */
  @Override
  void close();

  /**
   * Represents a received message with sender information.
   *
   * @param fromNodeId the node that sent the message
   * @param payload the message content
   */
  record Message(byte fromNodeId, byte[] payload) {}
}
