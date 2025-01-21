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

/// Wire protocol for Paxe secured network communication.
///
/// The protocol uses Data Encryption Keys (DEK) for efficient broadcast of large messages.
/// For payloads >64 bytes, we first encrypt the data once with a random DEK using AES-GCM,
/// then encrypt only the DEK itself with each recipient's session key. This avoids
/// re-encrypting large payloads multiple times during broadcast. Both the inner (DEK)
/// and outer (session key) encryption use AES-GCM with fresh IVs each time.
///
/// Datagram Structure:
/// ```
/// Header (8 bytes):
///   from:     2 bytes - source node ID
///   to:       2 bytes - destination node ID
///   channel:  2 bytes - protocol channel ID
///   length:   2 bytes - total payload length
///
/// Flags (1 byte):
///   bit 0:    1 = DEK encryption used, 0 = direct session key encryption
///   bits 1-7: reserved
///
/// For direct encryption (flags.bit0 == 0):
///   nonce:      12 bytes
///   payload:    N bytes AES-GCM encrypted with session key
///   auth_tag:   16 bytes
///
/// For DEK encryption (flags.bit0 == 1):
///   session_nonce:     12 bytes  - Fresh IV for session key encryption
///   session_auth_tag:  16 bytes  - Session key auth tag
///   encrypted_envelope: M bytes  - Fixed size envelope encrypted with session key containing:
///     dek_key:         16 bytes    - Random 128-bit DEK
///     dek_nonce:       12 bytes    - Fresh IV for DEK encryption
///     dek_auth_tag:    16 bytes    - DEK auth tag
///     dek_length:       2 bytes    - Length of DEK encrypted payload
///   dek_payload:       N bytes   - Payload encrypted with DEK
///```
public class PaxeNetwork implements NetworkLayer, AutoCloseable {
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
    Collection<NodeId> recipients = membershipSupplier.get().otherNodes(localNode);
    SessionKeyManager manager = keyManager;
    NodeId currentNode = localNode;
    for (NodeId recipient : recipients) {
      try {
        byte[] payload = serializeMessage(channel, msg);
        byte[] sessionKey = manager.sessionKeys.get(recipient);
        if (sessionKey == null) {
          throw new IllegalStateException("No session key for " + recipient);
        }

        // First encrypt with DEK - this is done once per broadcast
        byte[] baseEncrypted = PaxeCrypto.encrypt(payload, sessionKey);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + baseEncrypted.length);

        // Write header
        buffer.putShort(currentNode.id())
            .putShort(recipient.id())
            .putShort(channel.id())
            .putShort((short) baseEncrypted.length)
            .put(baseEncrypted);

        // Position after header
        buffer.position(HEADER_SIZE);

        // Encrypt DEK section for this recipient
        encryptDekSection(buffer, HEADER_SIZE, recipient);

        buffer.flip();
        SocketAddress address = resolveAddress(recipient);
        while (buffer.hasRemaining()) {
          this.channel.send(buffer, address);
        }
      } catch (IOException e) {
        LOGGER.warning(() -> String.format("Failed to send to %s: %s",
            recipient, e.getMessage()));
      }
    }
  }

  /**
   * Encrypts DEK with recipient's session key. The message payload remains encrypted with original DEK,
   * only the DEK encryption changes per recipient.
   * Protocol structure:
   * [flags:1][encrypted_dek:44][encrypted_payload]
   *
   * @param buffer       Message buffer containing DEK and payload
   * @param contentStart Start of encrypted content
   * @param toNodeId    Recipient node ID for session key lookup
   */
  private void encryptDekSection(ByteBuffer buffer, int contentStart, NodeId toNodeId) {
    // Skip mode flag byte
    int dekStart = contentStart + 1;

    // Get session key for recipient
    byte[] sessionKey = keyManager.sessionKeys.get(toNodeId);
    if (sessionKey == null) {
      throw new IllegalStateException("No session key for node: " + toNodeId);
    }

    // Extract DEK section
    byte[] dek = new byte[PaxeCrypto.DEK_SIZE];
    int savedPosition = buffer.position();
    buffer.position(dekStart);
    buffer.get(dek);

    // Encrypt DEK with recipient's session key
    byte[] encryptedDek = PaxeCrypto.encrypt(dek, sessionKey);

    // Write encrypted DEK back to buffer
    buffer.position(dekStart);
    buffer.put(encryptedDek, 0, PaxeCrypto.DEK_SECTION_SIZE);
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
