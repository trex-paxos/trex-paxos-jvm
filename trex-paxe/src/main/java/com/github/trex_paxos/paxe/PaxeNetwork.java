package com.github.trex_paxos.paxe;

import com.github.trex_paxos.network.*;
import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

  private record DirectBuffer(ByteBuffer sendBuffer, ByteBuffer receiveBuffer) {}

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
    DirectBuffer buffers = channelBuffers.get(channel);
    ByteBuffer buffer = buffers.sendBuffer();
    buffer.clear();

    // Write header
    buffer.putShort(localNode.id());
    buffer.putShort(to.id());
    buffer.putShort(channel.id());

    byte[] payload;
    if (channel == Channel.KEY_EXCHANGE) {
      payload = serializeKeyExchange(msg);
    } else {
      payload = serializeAndEncrypt(msg, to);
    }

    buffer.putShort((short)payload.length);
    buffer.put(payload);
    buffer.flip();

    try {
      SocketAddress address = resolveAddress(to);
      while (buffer.hasRemaining()) {
        this.channel.send(buffer, address);
      }
    } catch (IOException e) {
      LOGGER.warning("Failed to send message: " + e.getMessage());
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
      if (buffer.remaining() < HEADER_SIZE) continue;

      // Read header
      short fromId = buffer.getShort();
      short toId = buffer.getShort();
      short channelId = buffer.getShort();
      short length = buffer.getShort();

      if (toId != localNode.id()) continue;

      Channel msgChannel = new Channel(channelId);

      // Extract payload
      byte[] payload = new byte[length];
      buffer.get(payload);

      if (msgChannel == Channel.KEY_EXCHANGE) {
        handleKeyExchange(fromId, payload);
      } else {
        // Decrypt and dispatch to subscribers
        byte[] decrypted = decrypt(payload, new NodeId(fromId));
        dispatchToSubscribers(msgChannel, decrypted);
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

  private byte[] serializeAndEncrypt(Object msg, NodeId to) {
    // Get session key and encrypt
    byte[] key = keyManager.sessionKeys.get(to);
    if (key == null) {
      keyManager.initiateHandshake(to);
      throw new IllegalStateException("No session key available for " + to);
    }
    return PaxeCrypto.encrypt(serializeMessage(msg), key);
  }

  private byte[] decrypt(byte[] data, NodeId from) {
    byte[] key = keyManager.sessionKeys.get(from);
    if (key == null) {
      throw new IllegalStateException("No session key available from " + from);
    }
    return PaxeCrypto.decrypt(data, key);
  }

  private void handleKeyExchange(short fromId, byte[] payload) {
    SessionKeyManager.KeyMessage msg = PickleHandshake.unpickle(payload);
    keyManager.handleMessage(msg);
  }

  private byte[] serializeKeyExchange(Object msg) {
    return PickleHandshake.pickle((SessionKeyManager.KeyMessage)msg);
  }

  private byte[] serializeMessage(Object msg) {
    return ((byte[])msg);
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
