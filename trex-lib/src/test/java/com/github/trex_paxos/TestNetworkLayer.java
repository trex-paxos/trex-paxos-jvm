// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.ChannelSubscription;
import com.github.trex_paxos.network.NetworkLayer;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Consumer;

import static com.github.trex_paxos.TrexLogger.LOGGER;

public class TestNetworkLayer implements NetworkLayer {
  static final InMemoryNetwork network = new InMemoryNetwork();
  private final Map<Channel, Pickler<?>> picklers;

  TestNetworkLayer(@SuppressWarnings("unused") NodeId nodeId, Map<Channel, Pickler<?>> picklers) {
    this.picklers = Map.copyOf(picklers);
  }

  public <T> void subscribe(Channel channel, Consumer<T> handler, String name) {
    @SuppressWarnings("unchecked")
    Pickler<T> pickler = (Pickler<T>) picklers.get(channel);
    final var namedSubscriber = new ChannelSubscription(byteBuffer ->
        handler.accept(pickler.deserialize(byteBuffer)), name);
    LOGGER.fine(() -> "Subscribing to channel: " + channel + " with name: " + name);
    network.subscribe(channel, namedSubscriber);
  }

  public <T> void send(Channel channel, NodeId to, T msg) {
    @SuppressWarnings("unchecked")
    Pickler<T> pickler = (Pickler<T>) picklers.get(channel);
    if (pickler == null) {
      throw new IllegalStateException("No pickler registered for channel: " + channel);
    }
    final var buffer = ByteBuffer.allocate(pickler.sizeOf(msg));
    pickler.serialize(msg, buffer);
    network.send(channel, to.id(), buffer);
  }

  public void start() {
    network.start();
  }

  public void stop() {
    network.close();
  }

  @Override
  public void close() {
    stop();
  }
}
