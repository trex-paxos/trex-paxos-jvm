package com.github.trex_paxos;

import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.TrexNetwork;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import static com.github.trex_paxos.TrexLogger.LOGGER;

public class NetworkLayer {
  private final TrexNetwork network;
  private final Map<Channel, Pickler<?>> picklers;

  public NetworkLayer(TrexNetwork network, Map<Channel, Pickler<?>> picklers) {
    this.network = Objects.requireNonNull(network, "network cannot be null");
    this.picklers = Map.copyOf(picklers);
  }

  public <T> void subscribe(Channel channel, Consumer<T> handler, String name){
    @SuppressWarnings("unchecked")
    Pickler<T> pickler = (Pickler<T>) picklers.get(channel);
    final var namedSubscriber = new TrexNetwork.NamedSubscriber(byteBuffer -> handler.accept(pickler.deserialize(byteBuffer.array())), name);
    LOGGER.fine(() -> "Subscribing to channel: " + channel + " with name: " + name);
    network.subscribe(channel, namedSubscriber);
  }

  public <T> void send(Channel channel, short to, T msg) {
    @SuppressWarnings("unchecked")
    Pickler<T> pickler = (Pickler<T>) picklers.get(channel);
    if (pickler == null) {
      throw new IllegalStateException("No pickler registered for channel: " + channel);
    }
    byte[] data = pickler.serialize(msg);
    network.send(channel, to, ByteBuffer.wrap(data));
  }

  public <T> void broadcast(Channel channel, T msg, Set<Short> targets) {
    targets.forEach(target -> send(channel, target, msg));
  }

  public void start() {
    network.start();
  }

  public void stop() throws Exception {
    network.close();
  }
}

