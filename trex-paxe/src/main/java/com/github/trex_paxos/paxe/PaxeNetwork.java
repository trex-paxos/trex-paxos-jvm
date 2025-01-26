package com.github.trex_paxos.paxe;

import com.github.trex_paxos.Pickle;
import com.github.trex_paxos.Pickler;
import com.github.trex_paxos.network.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
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

  sealed interface Traffic {
    record Outbound<T>(Channel channel, NodeId to, T msg) implements Traffic {
    }

    record Inbound(Channel channel, NodeId from, byte[] payload) implements Traffic {
    }
  }

  private static final int MAX_PACKET_SIZE = 65507;
  private static final int QUEUE_CAPACITY = 1000;
  private static final int HEADER_SIZE = 8;

  final SessionKeyManager keyManager;
  final NodeId localNode;
  final DatagramChannel channel;
  private final Map<Channel, List<Consumer<?>>> subscribers;
  final Supplier<ClusterMembership> membership;
  private final Map<Channel, Pickler<?>> picklers;

  // Per-channel message queues
  private final Map<Channel, BlockingQueue<Traffic.Outbound<?>>> outboundQueues;
  private final Map<Channel, BlockingQueue<Traffic.Inbound>> inboundQueues;

  // Channel-specific threads
  private final Map<Channel, Thread> senderThreads;
  private final Map<Channel, Thread> receiverThreads;

  volatile boolean running;

  record PendingMessage(Channel channel, byte[] serializedData) {
  }

  private final Map<NodeId, Queue<PendingMessage>> pendingMessages = new ConcurrentHashMap<>();

  public static class Builder {
    private final Map<Channel, Pickler<?>> picklers = new HashMap<>();
    private final SessionKeyManager keyManager;
    private final int port;
    private final NodeId local;
    private final Supplier<ClusterMembership> membership;

    public Builder(SessionKeyManager keyManager, int port, NodeId local,
                   Supplier<ClusterMembership> membership) {
      Objects.requireNonNull(keyManager);
      Objects.requireNonNull(local);
      Objects.requireNonNull(membership);
      this.keyManager = keyManager;
      this.port = port;
      this.local = local;
      this.membership = membership;
      picklers.put(SystemChannel.CONSENSUS.value(), PickleMsg.instance);
      picklers.put(SystemChannel.PROXY.value(), Pickle.instance);
    }

    public <T> Builder addChannel(Channel channel, Pickler<T> pickler) {
      Objects.requireNonNull(channel);
      Objects.requireNonNull(pickler);
      picklers.put(channel, pickler);
      return this;
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

    this.outboundQueues = new ConcurrentHashMap<>();
    this.inboundQueues = new ConcurrentHashMap<>();
    this.senderThreads = new ConcurrentHashMap<>();
    this.receiverThreads = new ConcurrentHashMap<>();

    this.channel = DatagramChannel.open();
    this.channel.configureBlocking(false);
    this.channel.socket().bind(new InetSocketAddress(port));

    // Initialize queues for each channel
    for (Channel c : SystemChannel.systemChannels()) {
      outboundQueues.put(c, new LinkedBlockingQueue<>(QUEUE_CAPACITY));
      inboundQueues.put(c, new LinkedBlockingQueue<>(QUEUE_CAPACITY));
    }
  }

  @Override
  public <T> void send(Channel channel, NodeId to, T msg) {
    try {
      outboundQueues.get(channel).put(new Traffic.Outbound<>(channel, to, msg));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while queueing message", e);
    }
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
    subscribers.computeIfAbsent(channel, _ -> new ArrayList<>()).add(handler);
  }

  @Override
  public void start() {
    if (running) return;
    running = true;

    // Start channel-specific threads
    for (Channel c : SystemChannel.systemChannels()) {
      startChannelThreads(c);
    }
  }

  private void startChannelThreads(Channel channel) {
    Thread.Builder threadBuilder = switch (channel) {
      case Channel c when c.equals(SystemChannel.CONSENSUS.value()) ->
          Thread.ofPlatform().name("paxe-consensus-" + localNode.id());
      default -> Thread.ofVirtual().name("paxe-" + channel.id() + "-" + localNode.id());
    };

    senderThreads.put(channel, threadBuilder.start(() -> sendLoop(channel)));
    receiverThreads.put(channel, threadBuilder.start(() -> receiveLoop(channel)));
  }

  private void sendLoop(Channel channel) {
    ByteBuffer buffer = ByteBuffer.allocate(MAX_PACKET_SIZE);
    BlockingQueue<Traffic.Outbound<?>> queue = outboundQueues.get(channel);

    while (running) {
      try {
        Traffic.Outbound<?> msg = queue.take();
        sendMessage(msg, buffer);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        LOGGER.warning(() -> "Error in send loop: " + e);
      }
    }
  }

  private <T> void sendMessage(Traffic.Outbound<T> msg, ByteBuffer buffer) {
    try {
      buffer.clear();
      buffer.putShort(localNode.id());
      buffer.putShort(msg.to().id());
      buffer.putShort(msg.channel().id());

      byte[] payload;
      if (msg.channel().id() == KEY_EXCHANGE.id()) {
        payload = PickleHandshake.pickle((SessionKeyManager.KeyMessage) msg.msg());
      } else {
        byte[] key = keyManager.sessionKeys.get(msg.to());
        if (key == null) {
          bufferPendingMessage(msg);
          return;
        }
        payload = Crypto.encrypt(serializeMessage(msg.channel(), msg.msg()), key);
      }

      buffer.putShort((short) payload.length);
      buffer.put(payload);
      buffer.flip();

      SocketAddress address = resolveAddress(msg.to());
      channel.send(buffer, address);
    } catch (IOException e) {
      LOGGER.warning(() -> String.format("Failed to send to %s: %s", msg.to(), e));
    }
  }

  private <T> void bufferPendingMessage(Traffic.Outbound<T> msg) {
    byte[] serialized = serializeMessage(msg.channel(), msg.msg());
    Queue<PendingMessage> queue = pendingMessages.computeIfAbsent(msg.to(),
        _ -> new ConcurrentLinkedQueue<>());

    queue.add(new PendingMessage(msg.channel(), serialized));

    var handshake = keyManager.initiateHandshake(msg.to());
    handshake.ifPresent(keyMessage ->
        send(KEY_EXCHANGE.value(), msg.to(), keyMessage));
  }

  private void receiveLoop(Channel targetChannel) {
    ByteBuffer buffer = ByteBuffer.allocate(MAX_PACKET_SIZE);

    while (running) {
      try {
        buffer.clear();
        SocketAddress sender = channel.receive(buffer);
        if (sender == null) {
          Thread.sleep(1); // Avoid busy waiting
          continue;
        }

        buffer.flip();
        if (buffer.remaining() < HEADER_SIZE) {
          continue;
        }

        short fromId = buffer.getShort();
        short toId = buffer.getShort();
        short channelId = buffer.getShort();

        // Only process messages for our target channel
        Channel msgChannel = new Channel(channelId);
        if (!msgChannel.equals(targetChannel)) {
          continue;
        }

        short length = buffer.getShort();
        byte[] payload = new byte[length];
        buffer.get(payload);

        processInbound(new Traffic.Inbound(msgChannel, new NodeId(fromId), payload));
      } catch (Exception e) {
        if (running) {
          LOGGER.warning(() -> "Error in receive loop: " + e);
        }
      }
    }
  }

  private void processInbound(Traffic.Inbound msg) {
    if (msg.channel().id() == KEY_EXCHANGE.id()) {
      handleKeyExchange(msg.from().id(), msg.payload());
      return;
    }

    try {
      byte[] decrypted = decrypt(msg.payload(), msg.from());
      dispatchToSubscribers(msg.channel(), decrypted);
    } catch (Exception e) {
      LOGGER.warning(() -> String.format("Failed to process message from %d: %s",
          msg.from().id(), e.getMessage()));
    }
  }

  private byte[] decrypt(byte[] data, NodeId from) {
    byte[] key = keyManager.sessionKeys.get(from);
    if (key == null) {
      throw new IllegalStateException("No session key for " + from);
    }
    return Crypto.decrypt(data, key);
  }

  void handleKeyExchange(short fromId, byte[] payload) {
    SessionKeyManager.KeyMessage msg = PickleHandshake.unpickle(payload);
    keyManager.handleMessage(msg);
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

  private byte[] serializeMessage(Channel channel, Object msg) {
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

    for (Thread t : senderThreads.values()) {
      t.interrupt();
    }
    for (Thread t : receiverThreads.values()) {
      t.interrupt();
    }

    try {
      channel.close();
    } catch (IOException e) {
      LOGGER.warning("Error closing channel: " + e.getMessage());
    }
  }
}
