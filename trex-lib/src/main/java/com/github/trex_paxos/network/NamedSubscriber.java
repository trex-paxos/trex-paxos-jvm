package com.github.trex_paxos.network;

import java.nio.ByteBuffer;

public record NamedSubscriber(java.util.function.Consumer<ByteBuffer> handler, String name) {
  public void accept(ByteBuffer data) {
    handler.accept(data);
  }
}
