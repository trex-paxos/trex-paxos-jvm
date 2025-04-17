// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.network.*;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.github.trex_paxos.TrexLogger.LOGGER;

class TestNetworkLayer implements NetworkLayer {
  static final InMemoryNetwork network = new InMemoryNetwork();
  private final Map<Channel, Pickler<?>> picklers;
  private final NodeId nodeId;

  TestNetworkLayer(NodeId nodeId, Map<Channel, Pickler<?>> picklers) {
    this.picklers = Map.copyOf(picklers);
    this.nodeId = nodeId;
  }

  public <T> void subscribe(Channel channel, Consumer<T> handler, String name) {
    @SuppressWarnings("unchecked")
    Pickler<T> pickler = (Pickler<T>) picklers.get(channel);
    final var namedSubscriber = new ChannelSubscription(byteBuffer -> handler.accept(pickler.deserialize(byteBuffer.array())), name);
    LOGGER.fine(() -> "Subscribing to channel: " + channel + " with name: " + name);
    network.subscribe(channel, namedSubscriber);
  }

  public <T> void send(Channel channel, NodeId to, T msg) {
    @SuppressWarnings("unchecked")
    Pickler<T> pickler = (Pickler<T>) picklers.get(channel);
    if (pickler == null) {
      throw new IllegalStateException("No pickler registered for channel: " + channel);
    }
    byte[] data = pickler.serialize(msg);
    network.send(channel, to.id(), ByteBuffer.wrap(data));
  }

  @Override
  public <T> void broadcast(Supplier<NodeEndpoint> membershipSupplier, Channel channel, T msg) {
    NodeEndpoint membership = membershipSupplier.get();
    var others = new HashSet<>(membership.otherNodes(nodeId));
    others.forEach(nodeId -> send(channel, nodeId, msg));
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
