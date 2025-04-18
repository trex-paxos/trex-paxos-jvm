// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.Legislators;
import com.github.trex_paxos.NodeId;
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
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.github.trex_paxos.network.SystemChannel.*;
import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;
import static com.github.trex_paxos.paxe.PaxeProtocol.MAX_UDP_SIZE;

/// ## Paxe Network Protocol
///
/// This class is inspired by the QUIC protocol which is UDP used by HTTP3. Rather than using
/// public certificates we use RFC5054 SRP TLS to generated shared session keys between pairs of nodes.
/// Each UDP packet is encrypted with the session key using tamper-proof AES-GCM.
///
/// If the message is not broadcast message then it is simply encrypted with the session key for
/// the destination node. If the message is a broadcast message and greater than 64 bytes then it is first encrypted
/// random Data Encryption Key (DEK). The DEK is then encrypted with the session key of each recipient. This avoids
/// doing a lot of encryption work for messages that are bigger than a CPU cache line.
///
/// The protocol is limited to 63.8k payloads. Even a 63.8k payload is many frames over the network. Writing such a
/// large command into the Paxos log will be single threaded and make the log slower to run. A much better approach is
/// to have client write large commands into fault-tolerant cloud storage referenced by a UUID. Then use the UUID
/// as the command in the Paxos log. That way it is only consistency of ordering that is mediated by the Paxos protocol.
///
/// Paxe supports many channels to multiplex many parallel streams of data. Channel numbers below 100 are reserved for
/// protocol purposes. Channel numbers above 100 are for application use. Channel 0 is reserved for key exchange. Channel 1 is
///  system channels are used for Paxos and proxy.
///
/// # Datagram Structure
///
/// ```
/// Header (8 bytes):
///   from:     2 bytes - source node ID
///   to:       2 bytes - destination node ID
///   channel:  2 bytes - protocol channel ID
///   length:   2 bytes - total payload length
///
/// Flags (1 byte):
///   bit 0:    1 = DEK encryption used, 0 = direct session key encryption
///   bit 1:    magic bit, must be 0
///   bit 2:    magic bit, must be 1
///   bit 3-7:  reserved
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
///
/// Max payload sizes:
/// - Direct encryption: 65507 - 8 - 1 - 12 - 16 = 65470 bytes
/// - DEK encryption: 65507 - 8 - 1 - 12 - 16 - 16 - 12 - 16 - 2 = 65424 bytes
///```
///
/// TODO This logic uses a pickler that will geneerate a lot of byte arrays we should make one that writes to a pooled ByteBuffer
public class PaxeNetwork implements NetworkLayer, AutoCloseable {

  sealed interface Traffic {
    record Outbound<T>(Channel channel, NodeId to, T msg) implements Traffic {
    }

    record Inbound(Channel channel, NodeId from, byte[] payload) implements Traffic {
    }
  }

  static final int MAX_PACKET_SIZE = 65507; // TODO test the limits
  static final int HEADER_SIZE = 8;
  static final int MAX_BUFFERED_BYTES = 64240; // TODO test the limits
  static final int MAX_PAYLOAD_SIZE = 65424; // due to DEK encryption overhead

  final SessionKeyManager keyManager;
  final NodeId localNode;
  final DatagramChannel channel;
  final Selector selector;
  private final Map<Channel, List<Consumer<?>>> subscribers;
  final Supplier<NodeEndpoints> endpoints;
  private final Map<Channel, Pickler<?>> picklers;

  private volatile boolean running;

  private record PendingMessage(Channel channel, byte[] serializedData) {
  }

  // if we have no session key we buffer the message until we have one
  private final Map<NodeId, Queue<PendingMessage>> pendingMessages = new ConcurrentHashMap<>();

  // we want to offload processing inbound messages to different threads
  private final Map<Channel, BlockingQueue<Traffic.Inbound>> inboundQueues = new ConcurrentHashMap<>();
  // we want to offload processing outbound messages to different threads
  private final Map<Channel, BlockingQueue<Traffic.Outbound<?>>> outboundQueues = new ConcurrentHashMap<>();

  public static final class Builder {
    private final Map<Channel, Pickler<?>> picklers = new HashMap<>();
    private final SessionKeyManager keyManager;
    private final int port;
    private final NodeId local;
    private final Supplier<NodeEndpoints> endpointsSupplier;

    public Builder(SessionKeyManager keyManager, int port, NodeId local,
                   Supplier<NodeEndpoints> endpointsSupplier) {
      Objects.requireNonNull(keyManager, "Key manager cannot be null");
      Objects.requireNonNull(local, "Local node ID cannot be null");
      Objects.requireNonNull(endpointsSupplier, "Membership supplier cannot be null");
      this.keyManager = keyManager;
      this.port = port;
      this.local = local;
      this.endpointsSupplier = endpointsSupplier;
      picklers.put(CONSENSUS.value(), PickleMsg.instance);
      picklers.put(PROXY.value(), Pickle.instance);
      picklers.put(KEY_EXCHANGE.value(), PickleHandshake.instance);
    }

    public PaxeNetwork build() throws IOException {
      return new PaxeNetwork(keyManager, port, local, endpointsSupplier, picklers);
    }
  }

  PaxeNetwork(SessionKeyManager keyManager, int port, NodeId local,
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

      if (channel == CONSENSUS.value() || channel == PROXY.value()) {
        // Platform threads for critical system channels
        Thread.ofPlatform()
            .name("paxe-in-" + channel.id())
            .start(() -> processInbound(channel));
        Thread.ofPlatform()
            .name("paxe-out-" + channel.id())
            .start(() -> processOutbound(channel));
      } else {
        // Virtual threads for other channels
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
        // Process inbound messages
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
      final var serializeMessage = serializeMessage(msg, channel);
      if (serializeMessage.length >= MAX_PAYLOAD_SIZE) {
        throw new IllegalArgumentException("Serialized message %s too large: %d bytes".formatted(msg, serializeMessage.length));
      }
      payload = Crypto.encrypt(serializeMessage, key);
    }

    ByteBuffer buffer = ByteBuffer.allocateDirect(payload.length + HEADER_SIZE);
    buffer.clear();

    buffer.putShort(localNode.id());
    buffer.putShort(to.id());
    buffer.putShort(channel.id());

    buffer.putShort((short) payload.length);
    buffer.put(payload);
    buffer.flip();

    try {
      SocketAddress address = resolveAddress(to);
      int sent = this.channel.send(buffer, address);
      LOGGER.finest(() -> String.format("Sent %d bytes to %s", sent, address));
    } catch (java.nio.channels.ClosedChannelException e) {
      LOGGER.fine(() -> String.format("Failed to send message to %s: %s", to, "Channel closed"));
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
  public <T> void broadcast(Supplier<Legislators> membershipSupplier, Channel channel, T msg) {
    Collection<NodeId> recipients = membershipSupplier.get().otherNodes(localNode);
    byte[] serialized = serializeMessage(msg, channel);

    if (serialized.length <= PaxeProtocol.DEK_THRESHOLD) {
      // Small messages: Use standard per-recipient encryption
      recipients.forEach(recipient -> send(channel, recipient, msg));
    } else {
      try {
        // Large messages: Encrypt payload once with DEK
        var dekWithEncryptedPayload = Crypto.dekEncryptWithRandomKey(serialized);

        // Then only encrypt DEK per recipient
        for (NodeId recipient : recipients) {
          ByteBuffer output = ByteBuffer.allocateDirect(MAX_UDP_SIZE);
          Crypto.sessionKeyEncryptDek(output, dekWithEncryptedPayload, keyManager.sessionKeys.get(recipient));
          output.flip();
          // Send the encrypted DEK and reuse the encrypted payload
          send(channel, recipient, output);
        }
      } catch (GeneralSecurityException e) {
        throw new SecurityException("DEK encryption failed", e);
      }
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
    subscribe(KEY_EXCHANGE.value(), keyManager::handleMessage, "key-exchange");
    // Launch threads that consume from the queues
    initializeChannels();
    // Launch the hot core receiver thread that reads from the network
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
    if (readBuffer.remaining() < HEADER_SIZE) {
      LOGGER.finest(() -> String.format("Received undersized packet from %s: %d bytes",
          sender, readBuffer.remaining()));
      return;
    }

    short fromId = readBuffer.getShort();
    short toId = readBuffer.getShort();
    short channelId = readBuffer.getShort();
    short length = readBuffer.getShort();

    LOGGER.finest(() -> String.format("Read packet: from=%d, to=%d, channel=%d, len=%d",
        fromId, toId, channelId, length));

    if (toId != localNode.id()) {
      LOGGER.finest(() -> String.format("Packet not for us (to=%d, we are %d), dropping",
          toId, localNode.id()));
      return;
    }

    Channel msgChannel = new Channel(channelId);
    if (!inboundQueues.containsKey(msgChannel)) {
      LOGGER.warning(() -> String.format("Unknown channel %d", channelId));
      return;
    }

    LOGGER.finer(() -> String.format("Processing message from %d on channel %s",
        fromId, msgChannel));

    byte[] payload = new byte[length];
    readBuffer.get(payload);

    if (msgChannel.id() != KEY_EXCHANGE.id()) {
      try {
        byte[] decrypted = decrypt(payload, new NodeId(fromId));
        LOGGER.finest(() -> String.format("Dispatching %d byte message from %d on channel %s",
            decrypted.length, fromId, msgChannel));
        payload = decrypted;
      } catch (Exception e) {
        LOGGER.warning(() -> String.format("Failed to process message from %d: %s",
            fromId, e.getMessage()));
        return;
      }
    }

    inboundQueues.get(msgChannel).add(new Traffic.Inbound(msgChannel, new NodeId(fromId), payload));
  }

  private byte[] decrypt(byte[] data, NodeId from) {
    byte[] key = keyManager.sessionKeys.get(from);
    if (key == null) {
      throw new IllegalStateException("No session key for " + from);
    }
    return Crypto.decrypt(data, key);
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
