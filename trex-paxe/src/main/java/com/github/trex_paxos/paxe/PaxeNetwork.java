package com.github.trex_paxos.paxe;

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

import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;

public final class PaxeNetwork implements NetworkLayer, AutoCloseable {
  private static final int MAX_PACKET_SIZE = 65507; // UDP max size
  private static final int HEADER_SIZE = 8; // from(2) + to(2) + channel(2) + length(2)

  final SessionKeyManager keyManager;
  final NodeId localNode;
  final DatagramChannel channel;
  final Selector selector;
  private final Map<Channel, List<Consumer<byte[]>>> subscribers;
  private final Map<Channel, DirectBuffer> channelBuffers;
  final Supplier<ClusterMembership> membership;

  volatile boolean running;
  private Thread receiver;

  record DirectBuffer(ByteBuffer sendBuffer, ByteBuffer receiveBuffer) {
  }

  record PendingMessage(Channel channel, byte[] serializedData) {
  }

  private final Map<NodeId, Queue<PendingMessage>> pendingMessages = new ConcurrentHashMap<>();
  private static final int MAX_BUFFERED_BYTES = 65000;

  public PaxeNetwork(SessionKeyManager keyManager, int port, NodeId local,
                     Supplier<ClusterMembership> membership) throws IOException {
    this.keyManager = keyManager;
    this.localNode = local;
    this.membership = membership;
    this.subscribers = new ConcurrentHashMap<>();
    this.channelBuffers = new HashMap<>();

    // Initialize NIO components
    this.channel = DatagramChannel.open();
    this.channel.configureBlocking(false);
    this.channel.socket().bind(new InetSocketAddress(port));
    this.selector = Selector.open();
    this.channel.register(selector, SelectionKey.OP_READ);

    // Pre-allocate direct buffers for each channel type
    for (Channel c : Arrays.asList(Channel.CONSENSUS, Channel.PROXY, Channel.KEY_EXCHANGE)) {
      channelBuffers.put(c, new DirectBuffer(
          ByteBuffer.allocateDirect(MAX_PACKET_SIZE),
          ByteBuffer.allocateDirect(MAX_PACKET_SIZE)
      ));
    }
  }

  @Override
  public <T> void send(Channel channel, NodeId to, T msg) {
    LOGGER.finest(() -> String.format("Sending message on channel %s to %s", channel, to));
    DirectBuffer buffers = channelBuffers.get(channel);
    ByteBuffer buffer = buffers.sendBuffer();
    buffer.clear();

    // Write header
    buffer.putShort(localNode.id());
    buffer.putShort(to.id());
    buffer.putShort(channel.id());

    byte[] payload;
    if (channel == Channel.KEY_EXCHANGE) {
      LOGGER.finest(() -> "Processing key exchange message");
      payload = PickleHandshake.pickle((SessionKeyManager.KeyMessage) msg);
    } else {
      byte[] key = keyManager.sessionKeys.get(to);
      byte[] finalKey = key;
      LOGGER.finest(() -> String.format("Encrypting message for %d, key %s", to.id(),
          finalKey != null ? "present" : "missing"));
      if (key == null) {
        // Buffer message
        byte[] serialized = serializeMessage(msg);
        Queue<PendingMessage> queue = pendingMessages.computeIfAbsent(to, k -> new ConcurrentLinkedQueue<>());
        int queueBytes = queue.stream().mapToInt(m -> m.serializedData().length).sum();

        LOGGER.finest(() -> String.format("Buffering %d bytes for %s (total %d)", serialized.length, to, queueBytes));

        if (queueBytes + serialized.length > MAX_BUFFERED_BYTES) {
          throw new IllegalStateException("Message buffer full for " + to);
        }
        queue.add(new PendingMessage(channel, serialized));

        // Initiate handshake
        var handshake = keyManager.initiateHandshake(to);
        if (handshake.isPresent()) {
          send(Channel.KEY_EXCHANGE, to, handshake.get());
        }
        return;
      }
      payload = PaxeCrypto.encrypt(serializeMessage(msg), key);
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
    subscribers.computeIfAbsent(channel, k -> new ArrayList<>())
        .add((Consumer<byte[]>) handler);
  }

  @Override
  public <T> void broadcast(Supplier<ClusterMembership> membershipSupplier, Channel channel, T msg) {
    membershipSupplier.get().otherNodes(localNode).forEach(node -> send(channel, node, msg));
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

      LOGGER.finest(() -> String.format("Read packet: from=%d, to=%d, channel=%s, len=%d",
          fromId, toId, Channel.getSystemChannelName(channelId), length));

      if (toId != localNode.id()) {
        LOGGER.finest(() -> "Packet not for us, dropping");
        continue;
      }

      Channel msgChannel = new Channel(channelId);

      LOGGER.finest(() -> String.format("Processing message from %d on channel %s", fromId, msgChannel));

      // Extract payload
      byte[] payload = new byte[length];
      buffer.get(payload);

      if (msgChannel == Channel.KEY_EXCHANGE) {
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

  private void dispatchToSubscribers(Channel channel, byte[] msg) {
    List<Consumer<byte[]>> handlers = subscribers.get(channel);
    if (handlers != null) {
      for (Consumer<byte[]> handler : handlers) {
        handler.accept(msg);
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

  private byte[] serializeMessage(Object msg) {
    return ((byte[]) msg);
  }

  private SocketAddress resolveAddress(NodeId to) {
    NetworkAddress addr = membership.get().addressFor(to)
        .orElseThrow(() -> new IllegalStateException("No address for " + to));
    return new InetSocketAddress(addr.hostString(), addr.port());
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
