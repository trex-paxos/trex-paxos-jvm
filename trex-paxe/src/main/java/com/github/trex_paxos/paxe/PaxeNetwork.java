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

public final class PaxeNetwork implements NetworkLayer, AutoCloseable {
  private static final int MAX_PACKET_SIZE = 65507; // UDP max size
  private static final int HEADER_SIZE = 8; // from(2) + to(2) + channel(2) + length(2)

  final SessionKeyManager keyManager;
  final NodeId localNode;
  final DatagramChannel channel;
  final Selector selector;
  private final Map<Channel, List<Consumer<?>>> subscribers;
  private final Map<Channel, DirectBuffer> channelBuffers;
  final Supplier<ClusterMembership> membership;
  private final Map<Channel, Pickler<?>> picklers;

  volatile boolean running;
  private Thread receiver;

  record DirectBuffer(ByteBuffer sendBuffer, ByteBuffer receiveBuffer) {
  }

  record PendingMessage(Channel channel, byte[] serializedData) {
  }

  private final Map<NodeId, Queue<PendingMessage>> pendingMessages = new ConcurrentHashMap<>();
  private static final int MAX_BUFFERED_BYTES = 64240; // 44 * 1460 which is IPv6 MTU

  public static class Builder {
    private final Map<Channel, Pickler<?>> picklers = new HashMap<>();
    private final SessionKeyManager keyManager;
    private final int port;
    private final NodeId local;
    private final Supplier<ClusterMembership> membership;

    public <T> Builder addChannel(Channel channel, Pickler<T> pickler) {
      Objects.requireNonNull(channel, "Channel cannot be null");
      Objects.requireNonNull(pickler, "Pickler cannot be null");
      picklers.put(channel, pickler);
      return this;
    }

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

    // Initialize NIO components
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
    }
  }

  @Override
  public <T> void send(Channel channel, NodeId to, T msg) {
    LOGGER.finest(() -> String.format("%s Sending message on channel %s to %s", localNode, channel, to));
    DirectBuffer buffers = channelBuffers.get(channel);
    ByteBuffer buffer = buffers.sendBuffer();
    buffer.clear();

    // Write header
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
        // Buffer message
        byte[] serialized = serializeMessage(channel, msg);
        Queue<PendingMessage> queue = pendingMessages.computeIfAbsent(to, _ -> new ConcurrentLinkedQueue<>());
        int queueBytes = queue.stream().mapToInt(m -> m.serializedData().length).sum();

        LOGGER.finest(() -> String.format("Buffering %d bytes for %s (total %d)", serialized.length, to, queueBytes));

        if (queueBytes + serialized.length > MAX_BUFFERED_BYTES) {
          throw new IllegalStateException("Message buffer full for " + to);
        }
        queue.add(new PendingMessage(channel, serialized));

        // Initiate handshake
        var handshake = keyManager.initiateHandshake(to);
        handshake.ifPresent(keyMessage -> send(KEY_EXCHANGE.value(), to, keyMessage));
        return;
      }
      payload = PaxeCrypto.encrypt(serializeMessage(channel, msg), key);
    }

    buffer.putShort((short) payload.length);
    buffer.put(payload);
    buffer.flip();

    try {
      SocketAddress address = resolveAddress(to);
      while (buffer.hasRemaining()) {
        int sent = this.channel.send(buffer, address);
        LOGGER.finest(() -> String.format("Sent %d bytes to %s", sent, address));
      }
    } catch (IOException e) {
      LOGGER.warning(() -> String.format("Failed to send message to %s: %s", to, e.getMessage()));
      throw new RuntimeException(e);
    }
  }

  @Override
  public <T> void subscribe(Channel channel, Consumer<T> handler, String name) {
    subscribers.computeIfAbsent(channel, _ -> new ArrayList<>())
        .add(handler);
  }

  private final ThreadLocal<ByteBuffer> broadcastBuffer = ThreadLocal.withInitial(() ->
      ByteBuffer.allocateDirect(MAX_PACKET_SIZE));

  @Override
  public <T> void broadcast(Supplier<ClusterMembership> membershipSupplier, Channel channel, T msg) {
    byte[] payload = serializeMessage(channel, msg);
    Collection<NodeId> recipients = membershipSupplier.get().otherNodes(localNode);

    ByteBuffer buffer = broadcastBuffer.get();
    buffer.clear();

    // Write header template
    int headerStart = buffer.position();
    buffer.putShort(localNode.id())    // from
        .putShort((short) 0)          // to placeholder
        .putShort(channel.id());     // channel

    int lengthPos = buffer.position();
    buffer.putShort((short) 0);         // length placeholder
    int contentStart = buffer.position();

    // Initial encryption
    byte[] sessionKey = keyManager.sessionKeys.get(recipients.iterator().next());
    byte[] encrypted = PaxeCrypto.encrypt(payload, sessionKey);
    buffer.put(encrypted);

    int messageLength = buffer.position() - contentStart;
    buffer.putShort(lengthPos, (short) messageLength);
    int totalLength = buffer.position();

// Send to each recipient
    for (NodeId recipient : recipients) {
      try {
        // Update recipient id
        buffer.putShort(headerStart + 2, recipient.id());

        // If using DEK, encrypt DEK section
        if (encrypted[0] == PaxeCrypto.Mode.WITH_DEK.flag) {
          byte[] recipientKey = keyManager.sessionKeys.get(recipient);
          encryptDekSection(buffer, contentStart, recipientKey);
        }

        // Send
        buffer.position(0).limit(totalLength);
        SocketAddress address = resolveAddress(recipient);
        while (buffer.hasRemaining()) {
          this.channel.send(buffer, address);
        }

      } catch (IOException e) {
        LOGGER.warning("Failed to send to " + recipient + ": " + e.getMessage());
      }
    }
  }

  private void encryptDekSection(ByteBuffer buffer, int contentStart, byte[] newKey) {
    // Skip flags byte
    int dekStart = contentStart + 1;

    // Extract current DEK section
    byte[] encryptedSection = new byte[PaxeCrypto.DEK_SECTION_SIZE];
    int savedPosition = buffer.position();
    buffer.position(dekStart);
    buffer.get(encryptedSection);

    // encrypt with new session key
    byte[] encrypted = PaxeCrypto.reencryptDek(encryptedSection, null, newKey);

    // Write back
    buffer.position(dekStart);
    buffer.put(encrypted, 0, PaxeCrypto.DEK_SECTION_SIZE);
    buffer.position(savedPosition);
  }

  @Override
  public void start() {
    if (running) return;
    running = true;
    receiver = Thread.ofPlatform()
        .name("paxe-receiver-" + localNode.id())
        .start(this::receiveLoop);
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
          LOGGER.warning("Error in receive loop: " + e.getMessage());
        }
      }
    }
  }

  // FIXME this should be biased towards processing messages on the key exchange channel, then the consensus channel
  private void readFromChannel() throws IOException {
    // Rotate through receive buffers for each channel
    for (Map.Entry<Channel, DirectBuffer> entry : channelBuffers.entrySet()) {
      ByteBuffer buffer = entry.getValue().receiveBuffer();
      buffer.clear();

      SocketAddress sender = channel.receive(buffer);
      if (sender == null) continue;

      buffer.flip();
      if (buffer.remaining() < HEADER_SIZE) {
        LOGGER.finest(() -> String.format("Received undersized packet from %s: %d bytes", sender, buffer.remaining()));
        continue;
      }

      // Read header
      short fromId = buffer.getShort();
      short toId = buffer.getShort();
      short channelId = buffer.getShort();
      short length = buffer.getShort();

      LOGGER.finest(() -> String.format("Read packet: from=%d, to=%d, channel=%d, len=%d",
          fromId, toId, channelId, length));

      if (toId != localNode.id()) {
        LOGGER.finest(() -> "Packet not for us, dropping");
        continue;
      }

      Channel msgChannel = new Channel(channelId);

      LOGGER.finest(() -> String.format("Processing message from %d on channel %s", fromId, msgChannel));

      // Extract payload
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
          LOGGER.warning(() -> String.format("Failed to process message from %d: %s", fromId, e.getMessage()));
        }
      }
    }
  }

  private void dispatchToSubscribers(Channel channel, byte[] bytes) {
    Pickler<?> pickler = picklers.get(channel);
    if (pickler == null) {
      LOGGER.warning(() -> "No pickler for channel: " + channel);
      return;
    }
    final var msg = pickler.deserialize(bytes);
    List<Consumer<?>> handlers = subscribers.get(channel);
    if (handlers != null) {
      for (Consumer<?> handler : handlers) {
        //noinspection unchecked
        ((Consumer<Object>) handler).accept(msg);
      }
    }
  }

  private byte[] decrypt(byte[] data, NodeId from) {
    byte[] key = keyManager.sessionKeys.get(from);
    if (key == null) {
      throw new IllegalStateException("No session key available from " + from);
    }
    return PaxeCrypto.decrypt(data, key);
  }

  /// For key exchange, deserialize without encryption
  void handleKeyExchange(short fromId, byte[] payload) {
    LOGGER.finest(() -> String.format("Processing key exchange message from %d", fromId));
    SessionKeyManager.KeyMessage msg = PickleHandshake.unpickle(payload);
    keyManager.handleMessage(msg);
  }

  private byte[] serializeMessage(Channel channel, Object msg) {
    LOGGER.finest(() -> String.format("Serializing message of type: %s", msg.getClass().getName()));
    Pickler<?> pickler = picklers.get(channel);
    if (pickler == null) {
      throw new IllegalStateException("No pickler for channel: " + channel);
    }
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
    if (receiver != null) {
      receiver.interrupt();
      receiver = null;
    }
  }
}
