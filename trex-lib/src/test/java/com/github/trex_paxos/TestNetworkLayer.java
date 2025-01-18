package com.github.trex_paxos;

import com.github.trex_paxos.network.*;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.github.trex_paxos.TrexLogger.LOGGER;

public class TestNetworkLayer implements NetworkLayer {
  private final InMemoryNetwork network;
  private final Map<Channel, Pickler<?>> picklers;
  private final NodeId nodeId;

  public TestNetworkLayer(NodeId nodeId, InMemoryNetwork network, Map<Channel, Pickler<?>> picklers) {
    this.network = Objects.requireNonNull(network, "network cannot be null");
    this.picklers = Map.copyOf(picklers);
    this.nodeId = nodeId;
  }

  public <T> void subscribe(Channel channel, Consumer<T> handler, String name){
    @SuppressWarnings("unchecked")
    Pickler<T> pickler = (Pickler<T>) picklers.get(channel);
    final var namedSubscriber = new NamedSubscriber(byteBuffer -> handler.accept(pickler.deserialize(byteBuffer.array())), name);
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
  public <T> void broadcast(Supplier<ClusterMembership> membershipSupplier, Channel channel, T msg) {
    ClusterMembership membership = membershipSupplier.get();
    var others = new HashSet<>(membership.otherNodes(nodeId));
    others.forEach(nodeId -> send(channel, nodeId, msg));
  }

  public void start() {
    network.start();
  }

  public void stop() throws Exception {
    network.close();
  }

  @Override
  public void close() throws Exception {
    stop();
  }
}
