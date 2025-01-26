package com.github.trex_paxos.paxe;

import com.github.trex_paxos.Pickle;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.github.trex_paxos.network.SystemChannel.KEY_EXCHANGE;
import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;

/// Wire protocol for Paxe secured network communication
public final class PaxeNetwork implements NetworkLayer, AutoCloseable {
  private static final int MAX_PACKET_SIZE = 65507;
  private static final int HEADER_SIZE = 8;
  private static final int MAX_BUFFERED_BYTES = 64240;

  final SessionKeyManager keyManager;
  final NodeId localNode;
  final DatagramChannel channel;
  final Selector selector;
  private final Map<Channel, List<Consumer<?>>> subscribers;
  private final Map<Channel, DirectBuffer> channelBuffers;
  final Supplier<ClusterMembership> membership;
  private final Map<Channel, Pickler<?>> picklers;

  private volatile boolean running;

  private record DirectBuffer(ByteBuffer sendBuffer, ByteBuffer receiveBuffer) {
  }

  private record PendingMessage(Channel channel, byte[] serializedData) {
  }

  private final Map<NodeId, Queue<PendingMessage>> pendingMessages = new ConcurrentHashMap<>();

  public static final class Builder {
    private final Map<Channel, Pickler<?>> picklers = new HashMap<>();
    private final SessionKeyManager keyManager;
    private final int port;
    private final NodeId local;
    private final Supplier<ClusterMembership> membership;

    public Builder(SessionKeyManager keyManager, int port, NodeId local,
                   Supplier<ClusterMembership> membership) {
      Objects.requireNonNull(keyManager, "Key manager cannot be null");
      Objects.requireNonNull(local, "Local node ID cannot be null");
      Objects.requireNonNull(membership, "Membership supplier cannot be null");
      this.keyManager = keyManager;
      this.port = port;
      this.local = local;
      this.membership = membership;
      picklers.put(SystemChannel.CONSENSUS.value(), PickleMsg.instance);
      picklers.put(SystemChannel.PROXY.value(), Pickle.instance);
    }

    public PaxeNetwork build() throws IOException {
      return new PaxeNetwork(keyManager, port, local, membership, picklers);
    }
  }

  PaxeNetwork(SessionKeyManager keyManager, int port, NodeId local,
              Supplier<ClusterMembership> membership,
              Map<Channel, Pickler<?>> picklers) throws IOException {
    this.keyManager = keyManager;
    this.localNode = local;
    this.membership = membership;
    this.picklers = Map.copyOf(picklers);
    this.subscribers = new ConcurrentHashMap<>();
    this.channelBuffers = new HashMap<>();

    LOGGER.fine(() -> String.format("Initializing network for node %s on port %d", local, port));

    this.channel = DatagramChannel.open();
    this.channel.configureBlocking(false);
    this.channel.socket().bind(new InetSocketAddress(port));
    this.selector = Selector.open();
    this.channel.register(selector, SelectionKey.OP_READ);

    // Pre-allocate direct buffers for each channel type
    for (Channel c : SystemChannel.systemChannels()) {
      channelBuffers.put(c, new DirectBuffer(
          ByteBuffer.allocateDirect(MAX_PACKET_SIZE),
          ByteBuffer.allocateDirect(MAX_PACKET_SIZE)
      ));
      LOGGER.finest(() -> String.format("Allocated buffers for channel %s", c));
    }
  }

  @Override
  public <T> void send(Channel channel, NodeId to, T msg) {
    LOGGER.finest(() -> String.format("%s Sending message on channel %s to %s: %s",
        localNode, channel, to, msg));

    DirectBuffer buffers = channelBuffers.get(channel);
    ByteBuffer buffer = buffers.sendBuffer();
    buffer.clear();

    buffer.putShort(localNode.id());
    buffer.putShort(to.id());
    buffer.putShort(channel.id());

    byte[] payload;
    if (channel.id() == KEY_EXCHANGE.id()) {
      LOGGER.finest(() -> "Processing key exchange message");
      payload = PickleHandshake.pickle((SessionKeyManager.KeyMessage) msg);
    } else {
      byte[] key = keyManager.sessionKeys.get(to);
      LOGGER.finest(() -> String.format("Encrypting message for %d, key %s", to.id(),
          key != null ? "present" : "missing"));
      if (key == null) {
        bufferPendingMessage(channel, to, msg);
        return;
      }
      payload = Crypto.encrypt(serializeMessage(msg, channel), key);
    }

    buffer.putShort((short) payload.length);
    buffer.put(payload);
    buffer.flip();

    try {
      SocketAddress address = resolveAddress(to);
      int sent = this.channel.send(buffer, address);
      LOGGER.finest(() -> String.format("Sent %d bytes to %s", sent, address));
    } catch (IOException e) {
      LOGGER.warning(() -> String.format("Failed to send message to %s: %s", to, e.getMessage()));
      throw new RuntimeException(e);
    }
  }

  private <T> void bufferPendingMessage(Channel channel, NodeId to, T msg) {
    byte[] serialized = serializeMessage(msg, channel);
    Queue<PendingMessage> queue = pendingMessages.computeIfAbsent(to, _ -> new ConcurrentLinkedQueue<>());
    int queueBytes = queue.stream().mapToInt(m -> m.serializedData().length).sum();

    LOGGER.finest(() -> String.format("Buffering %d bytes for %s (total %d)",
        serialized.length, to, queueBytes));

    if (queueBytes + serialized.length > MAX_BUFFERED_BYTES) {
      throw new IllegalStateException("Message buffer full for " + to);
    }
    queue.add(new PendingMessage(channel, serialized));

    var handshake = keyManager.initiateHandshake(to);
    handshake.ifPresent(keyMessage -> send(KEY_EXCHANGE.value(), to, keyMessage));
  }

  @Override
  public <T> void broadcast(Supplier<ClusterMembership> membershipSupplier, Channel channel, T msg) {
    Collection<NodeId> recipients = membershipSupplier.get().otherNodes(localNode);
    for (NodeId recipient : recipients) {
      send(channel, recipient, msg);
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

  private void readFromChannel() throws IOException {
    for (Map.Entry<Channel, DirectBuffer> entry : channelBuffers.entrySet()) {
      ByteBuffer buffer = entry.getValue().receiveBuffer();
      buffer.clear();

      SocketAddress sender = channel.receive(buffer);
      if (sender == null) continue;

      buffer.flip();
      if (buffer.remaining() < HEADER_SIZE) {
        LOGGER.finest(() -> String.format("Received undersized packet from %s: %d bytes",
            sender, buffer.remaining()));
        continue;
      }

      short fromId = buffer.getShort();
      short toId = buffer.getShort();
      short channelId = buffer.getShort();
      short length = buffer.getShort();

      LOGGER.finest(() -> String.format("Read packet: from=%d, to=%d, channel=%d, len=%d",
          fromId, toId, channelId, length));

      if (toId != localNode.id()) {
        LOGGER.finest(() -> String.format("Packet not for us (to=%d, we are %d), dropping",
            toId, localNode.id()));
        continue;
      }

      Channel msgChannel = new Channel(channelId);
      LOGGER.finest(() -> String.format("Processing message from %d on channel %s",
          fromId, msgChannel));

      byte[] payload = new byte[length];
      buffer.get(payload);

      if (msgChannel.id() == KEY_EXCHANGE.id()) {
        LOGGER.finest(() -> String.format("Processing key exchange from %d", fromId));
        handleKeyExchange(fromId, payload);
      } else {
        try {
          byte[] decrypted = decrypt(payload, new NodeId(fromId));
          LOGGER.finest(() -> String.format("Dispatching %d byte message from %d on channel %s",
              decrypted.length, fromId, msgChannel));
          dispatchToSubscribers(msgChannel, decrypted);
        } catch (Exception e) {
          LOGGER.warning(() -> String.format("Failed to process message from %d: %s",
              fromId, e.getMessage()));
        }
      }
    }
  }

  private byte[] decrypt(byte[] data, NodeId from) {
    byte[] key = keyManager.sessionKeys.get(from);
    if (key == null) {
      throw new IllegalStateException("No session key for " + from);
    }
    return Crypto.decrypt(data, key);
  }

  private void handleKeyExchange(short fromId, byte[] payload) {
    LOGGER.finest(() -> String.format("Processing key exchange message from %d", fromId));
    SessionKeyManager.KeyMessage msg = PickleHandshake.unpickle(payload);
    keyManager.handleMessage(msg);
  }

  private void dispatchToSubscribers(Channel channel, byte[] bytes) {
    Pickler<?> pickler = picklers.get(channel);
    if (pickler == null) {
      LOGGER.warning(() -> String.format("No pickler for channel %s", channel));
      return;
    }

    Object msg = pickler.deserialize(bytes);
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
    Pickler<?> pickler = picklers.get(channel);
    if (pickler == null) {
      throw new IllegalStateException("No pickler for channel: " + channel);
    }
    //noinspection unchecked
    return ((Pickler<Object>) pickler).serialize(msg);
  }

  private SocketAddress resolveAddress(NodeId to) {
    NetworkAddress address = membership.get().addressFor(to)
        .orElseThrow(() -> new IllegalStateException("No address for " + to));
    return new InetSocketAddress(address.hostString(), address.port());
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
