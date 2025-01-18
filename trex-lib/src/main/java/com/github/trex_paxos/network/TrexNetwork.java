package com.github.trex_paxos.network;

import java.nio.ByteBuffer;

/**
 * Transport agnostic network interface for Trex consensus.
 * Implementations may use any transport (UDP, TCP, message bus etc).
 */
public interface TrexNetwork extends AutoCloseable {
  record NamedSubscriber(java.util.function.Consumer<ByteBuffer> handler, String name) {
    public void accept(ByteBuffer data) {
      handler.accept(data);
    }
  }

  /**
   * Send data to a specific node.
   * @param channel The logical channel for the message
   * @param to Target node identifier
   * @param data The message payload
   */
  void send(Channel channel, short to, ByteBuffer data);

  /**
   * Send data to all nodes except self.
   * @param channel The logical channel for the message
   * @param data The message payload
   */
  default void broadcast(Channel channel, ByteBuffer data) {
    throw new UnsupportedOperationException("Broadcast not implemented");
  }

  /**
   * Register channel handler
   * @param channel The channel to subscribe to
   * @param handler The message handler
   */
  void subscribe(Channel channel, NamedSubscriber handler);

  /**
   * Start network processing
   */
  void start();
}
