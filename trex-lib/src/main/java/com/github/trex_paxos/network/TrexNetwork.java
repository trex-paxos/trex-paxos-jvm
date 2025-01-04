package com.github.trex_paxos.network;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public interface TrexNetwork {
  // Async send without response handling
  void send(Channel channel, ByteBuffer data);

  // Async send with response handling
  void send(Channel channel, ByteBuffer data, Consumer<ByteBuffer> responseHandler);

  // Register channel handler
  void subscribe(Channel channel, Consumer<ByteBuffer> handler);

  // Lifecycle management
  void start();

  void stop();
}
