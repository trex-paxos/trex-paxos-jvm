package com.github.trex_paxos.network;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public interface TrexNetwork {
  record NamedSubscriber(Consumer<ByteBuffer> handler, String name) {
    public void accept(ByteBuffer data) {
      handler.accept(data);
    }
  }
  // Async send to a single node
  void send(Channel channel, short to, ByteBuffer data);

  // Register channel handler
  void subscribe(Channel channel, NamedSubscriber handler);

  // Lifecycle management
  void start();

  void stop();
}
