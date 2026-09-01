// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.CommandPickler;
import com.github.trex_paxos.NodeId;
import com.github.trex_paxos.Pickler;
import com.github.trex_paxos.network.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.github.trex_paxos.network.SystemChannel.*;
import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;

/// UDP datagram codec for Trex Paxos using cluster-wide pre-shared keys and AES-256-GCM.
///
/// Each member holds the same 32-byte cluster PSK per epoch, installed out of band. PAXE does not
/// negotiate keys, distribute verifiers, or establish pairwise session state. `fromId` in the
/// prefix is authenticated routing metadata, not a distinct cryptographic node identity.
public class PaxeNetwork implements NetworkLayer, AutoCloseable {

  sealed interface Traffic {
    record Outbound<T>(Channel channel, NodeId to, T msg) implements Traffic {
    }

    record Inbound(Channel channel, NodeId from, byte[] payload) implements Traffic {
    }
  }

  static final int MAX_PACKET_SIZE = PaxeProtocol.MAX_UDP_SIZE;

  final ClusterKeyManager keyManager;
  final NodeId localNode;
  final DatagramChannel channel;
  final Selector selector;
  private final Map<Channel, List<Consumer<?>>> subscribers;
  final Supplier<NodeEndpoints> endpoints;
  private final Map<Channel, Pickler<?>> picklers;

  private volatile boolean running;

  private final Map<Channel, BlockingQueue<Traffic.Inbound>> inboundQueues = new ConcurrentHashMap<>();
  private final Map<Channel, BlockingQueue<Traffic.Outbound<?>>> outboundQueues = new ConcurrentHashMap<>();

  public static final class Builder {
    private final Map<Channel, Pickler<?>> picklers = new HashMap<>();
    private final ClusterKeyManager keyManager;
    private final int port;
    private final NodeId local;
    private final Supplier<NodeEndpoints> endpointsSupplier;

    public Builder(ClusterKeyManager keyManager, int port, NodeId local,
                   Supplier<NodeEndpoints> endpointsSupplier) {
      Objects.requireNonNull(keyManager, "Key manager cannot be null");
      Objects.requireNonNull(local, "Local node ID cannot be null");
      Objects.requireNonNull(endpointsSupplier, "Membership supplier cannot be null");
      this.keyManager = keyManager;
      this.port = port;
      this.local = local;
      this.endpointsSupplier = endpointsSupplier;
      picklers.put(CONSENSUS.value(), PickleMsg.instance);
      picklers.put(PROXY.value(), CommandPickler.instance);
    }

    public PaxeNetwork build() throws IOException {
      return new PaxeNetwork(keyManager, port, local, endpointsSupplier, picklers);
    }
  }

  PaxeNetwork(ClusterKeyManager keyManager, int port, NodeId local,
              Supplier<NodeEndpoints> endpoints,
              Map<Channel, Pickler<?>> picklers) throws IOException {
    this.keyManager = keyManager;
    this.localNode = local;
    this.endpoints = endpoints;
    this.picklers = Map.copyOf(picklers);
    this.subscribers = new ConcurrentHashMap<>();

    LOGGER.fine(() -> String.format("Initializing network for node %s on port %d", local, port));

    this.channel = DatagramChannel.open();
    this.channel.configureBlocking(false);
    this.channel.socket().bind(new InetSocketAddress(port));
    this.selector = Selector.open();
    this.channel.register(selector, SelectionKey.OP_READ);
  }

  protected void initializeChannels() {
    SystemChannel.systemChannels().forEach(channel -> {
      inboundQueues.put(channel, new ArrayBlockingQueue<>(1000));
      outboundQueues.put(channel, new ArrayBlockingQueue<>(1000));

      if (channel.equals(CONSENSUS.value()) || channel.equals(PROXY.value())) {
        Thread.ofPlatform()
            .name("paxe-in-" + channel.id())
            .start(() -> processInbound(channel));
        Thread.ofPlatform()
            .name("paxe-out-" + channel.id())
            .start(() -> processOutbound(channel));
      } else {
        Thread.ofVirtual()
            .name("paxe-in-" + channel.id())
            .start(() -> processInbound(channel));
        Thread.ofVirtual()
            .name("paxe-out-" + channel.id())
            .start(() -> processOutbound(channel));
      }
    });
  }

  protected void processInbound(Channel channel) {
    while (running) {
      try {
        Traffic.Inbound traffic = inboundQueues.get(channel).take();
        dispatchToSubscribers(channel, traffic.payload());
      } catch (InterruptedException e) {
        if (running) {
          LOGGER.warning("Inbound processing interrupted: " + e.getMessage());
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  protected void processOutbound(Channel channel) {
    while (running) {
      try {
        Traffic.Outbound<?> traffic = outboundQueues.get(channel).take();
        send(channel, traffic.to(), traffic.msg());
      } catch (InterruptedException e) {
        if (running) {
          LOGGER.warning("Outbound processing interrupted: " + e.getMessage());
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @Override
  public <T> void send(Channel channel, NodeId to, T msg) {
    if (!running) {
      return;
    }
    if (to.id() == localNode.id()) {
      LOGGER.finest(() -> String.format("Ignoring message to self on channel %s: %s", channel, msg));
      return;
    }

    LOGGER.finest(() -> String.format("%s Sending message on channel %s to %s: %s",
        localNode, channel, to, msg));

    byte[] serialized = serializeMessage(msg, channel);
    if (serialized.length > PaxeProtocol.MAX_PLAINTEXT_SIZE) {
      throw new IllegalArgumentException("Serialized message %s too large: %d bytes".formatted(msg, serialized.length));
    }

    try {
      byte epoch = keyManager.currentEpoch();
      byte[] clusterKey = keyManager.keyForEpoch(epoch);
      PaxePacket packet = PaxePacket.seal(localNode, to, channel, epoch, serialized, clusterKey);
      ByteBuffer buffer = ByteBuffer.wrap(packet.toDatagram());
      SocketAddress address = resolveAddress(to);
      int sent = this.channel.send(buffer, address);
      LOGGER.finest(() -> String.format("Sent %d bytes to %s", sent, address));
    } catch (java.nio.channels.ClosedChannelException e) {
      LOGGER.fine(() -> String.format("Failed to send message to %s: %s", to, "Channel closed"));
    } catch (Exception e) {
      LOGGER.warning(() -> String.format("Failed to send message to %s: %s", to, e.getMessage()));
      throw new RuntimeException(e);
    }
  }

  @Override
  public <T> void subscribe(Channel channel, Consumer<T> handler, String name) {
    LOGGER.finest(() -> String.format("Adding subscriber %s to channel %s", name, channel));
    subscribers.computeIfAbsent(channel, _ -> new ArrayList<>()).add(handler);
  }

  @Override
  public void start() {
    if (running) return;
    running = true;
    initializeChannels();
    Thread.ofPlatform()
        .name("paxe-receiver-" + localNode.id())
        .start(this::receiveLoop);
    LOGGER.fine(() -> String.format("Started receiver thread for node %s", localNode));
  }

  private void receiveLoop() {
    while (running) {
      try {
        if (selector.select() > 0) {
          Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
          while (selectedKeys.hasNext()) {
            SelectionKey key = selectedKeys.next();
            selectedKeys.remove();

            if (key.isReadable()) {
              readFromChannel();
            }
          }
        }
      } catch (IOException e) {
        if (running) {
          LOGGER.warning(() -> "Error in receive loop: " + e.getMessage());
        }
      }
    }
  }

  private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(MAX_PACKET_SIZE);

  private void readFromChannel() throws IOException {
    readBuffer.clear();
    SocketAddress sender = channel.receive(readBuffer);
    if (sender == null) return;

    readBuffer.flip();
    if (readBuffer.remaining() < PaxePacket.FRAME_OVERHEAD) {
      LOGGER.finest(() -> String.format("Received undersized packet from %s: %d bytes",
          sender, readBuffer.remaining()));
      return;
    }

    byte[] datagram = new byte[readBuffer.remaining()];
    readBuffer.get(datagram);

    try {
      PaxePacket packet = PaxePacket.fromDatagram(datagram);

      if (packet.to().id() != localNode.id()) {
        LOGGER.finest(() -> String.format("Packet not for us (to=%d, we are %d), dropping",
            packet.to().id(), localNode.id()));
        return;
      }

      Channel msgChannel = packet.channel();
      if (!inboundQueues.containsKey(msgChannel)) {
        LOGGER.warning(() -> String.format("Unknown channel %d", msgChannel.id()));
        return;
      }

      byte[] clusterKey = keyManager.keyForEpoch(packet.epoch());
      byte[] payload = packet.decrypt(clusterKey);

      LOGGER.finest(() -> String.format("Dispatching %d byte message from %d on channel %s",
          payload.length, packet.from().id(), msgChannel));

      inboundQueues.get(msgChannel).add(new Traffic.Inbound(msgChannel, packet.from(), payload));
    } catch (SecurityException e) {
      LOGGER.warning(() -> String.format("Rejected datagram from %s: %s", sender, e.getMessage()));
    }
  }

  private void dispatchToSubscribers(Channel channel, byte[] bytes) {
    Pickler<?> pickler = picklers.get(channel);
    if (pickler == null) {
      LOGGER.warning(() -> String.format("No pickler for channel %s", channel));
      return;
    }

    Object msg = pickler.deserialize(ByteBuffer.wrap(bytes));
    LOGGER.finest(() -> String.format("Deserialized message on channel %s: %s", channel, msg));

    List<Consumer<?>> handlers = subscribers.get(channel);
    if (handlers != null) {
      for (Consumer<?> handler : handlers) {
        LOGGER.finest(() -> String.format("Invoking handler for message on channel %s", channel));
        //noinspection unchecked
        ((Consumer<Object>) handler).accept(msg);
      }
    }
  }

  private <T> byte[] serializeMessage(T msg, Channel channel) {
    LOGGER.finest(() -> String.format("Serializing message type: %s", msg.getClass().getName()));
    @SuppressWarnings("unchecked")
    Pickler<Object> pickler = (Pickler<Object>) picklers.get(channel);
    if (pickler == null) {
      throw new IllegalStateException("No pickler for channel: " + channel);
    }
    int size = pickler.sizeOf(msg);
    ByteBuffer buffer = ByteBuffer.allocate(size);
    pickler.serialize(msg, buffer);

    byte[] result = new byte[buffer.position()];
    buffer.flip();
    buffer.get(result);
    return result;
  }

  private SocketAddress resolveAddress(NodeId to) {
    NetworkAddress address = endpoints.get().addressFor(to)
        .orElseThrow(() -> new IllegalStateException("No address for " + to));
    return new InetSocketAddress(address.host(), address.port());
  }

  @Override
  public void close() {
    running = false;
    if (selector != null) {
      selector.wakeup();
      try {
        selector.close();
      } catch (IOException e) {
        LOGGER.warning("Error closing selector: " + e.getMessage());
      }
    }
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException e) {
        LOGGER.warning("Error closing channel: " + e.getMessage());
      }
    }
  }
}
