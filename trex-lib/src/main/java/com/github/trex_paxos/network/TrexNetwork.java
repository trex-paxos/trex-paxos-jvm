package com.github.trex_paxos.network;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public interface TrexNetwork {
  // Async send to a single node
  void send(Channel channel, short to, ByteBuffer data);

  // Register channel handler
  void subscribe(Channel channel, Consumer<ByteBuffer> handler);

  // Lifecycle management
  void start();

  void stop();
}
