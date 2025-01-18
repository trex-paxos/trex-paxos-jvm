package com.github.trex_paxos.paxe;

import com.github.trex_paxos.Pickler;
import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.ClusterMembership;
import com.github.trex_paxos.network.NetworkAddress;
import com.github.trex_paxos.network.NodeId;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;

public final class PaxeNetwork implements AutoCloseable {
  private final SessionKeyManager keyManager;
  private final NodeId localNode;
  private final DatagramSocket socket;
  private final ByteBuffer receiveBuffer;
  private final Map<Integer, ChannelHandler<?>> handlers;
  private final ClusterMembership membership;

  private volatile boolean running;
  private Thread receiver;

  private record ChannelHandler<T>(Channel channel, Consumer<T> handler) {}

  public PaxeNetwork(SessionKeyManager keyManager, int port, NodeId local, ClusterMembership membership) throws IOException {
    this.keyManager = keyManager;
    this.localNode = local;
    this.socket = new DatagramSocket(port);
    this.receiveBuffer = ByteBuffer.allocateDirect(65535 + 8); // header + max payload
    this.handlers = new ConcurrentHashMap<>();
    this.membership = membership;
  }

  public <T> void subscribe(Channel channel, Consumer<T> handler) {
    handlers.put((int)channel.id(), new ChannelHandler<>(channel, handler));
  }

  public <T> void send(Channel channel, NodeId to, T msg) throws IOException, GeneralSecurityException {
    byte[] payload = channel.pickler().serialize(msg);
    NetworkAddress addr = membership.addressFor(to).orElseThrow();
    byte[] header = PaxeHeader.toBytes(localNode.id(), to.id(), channel.id(), payload.length);

    if (channel == Channel.KEY_EXCHANGE) {
      sendDatagram(addr, header, payload);
    } else {
      byte[] key = keyManager.sessionKeys.get(to);
      if (key == null) {
        keyManager.initiateHandshake(to);
        return;
      }
      byte[] encrypted = Crypto.encrypt(key, payload);
      sendDatagram(addr, header, encrypted);
    }
  }

  public <T> void broadcast(Channel channel, T msg) throws IOException, GeneralSecurityException {
    for (NodeId node : membership.otherNodes(localNode)) {
      send(channel, node, msg);
    }
  }

  private void sendDatagram(NetworkAddress addr, byte[] header, byte[] payload) throws IOException {
    byte[] combined = new byte[header.length + payload.length];
    System.arraycopy(header, 0, combined, 0, header.length);
    System.arraycopy(payload, 0, combined, header.length, payload.length);

    DatagramPacket packet = new DatagramPacket(
        combined, combined.length,
        InetAddress.getByName(addr.hostString()),
        addr.port()
    );
    socket.send(packet);
  }

  public void start() {
    if (running) return;
    running = true;
    receiver = Thread.ofPlatform()
        .name("paxe-receiver-" + localNode.id())
        .start(this::receiveLoop);
  }

  private void receiveLoop() {
    int[] headerValues = new int[4];
    DatagramPacket packet = new DatagramPacket(receiveBuffer.array(), receiveBuffer.capacity());

    while (running) {
      try {
        socket.receive(packet);
        if (packet.getLength() < 8) continue;

        byte[] data = packet.getData();
        PaxeHeader.unpack(data, headerValues);
        int fromNode = headerValues[0];
        int toNode = headerValues[1];
        int channelId = headerValues[2];
        int length = headerValues[3];

        if (toNode != localNode.id()) continue;

        ChannelHandler<?> handler = handlers.get(channelId);
        if (handler == null) continue;

        processMessage(handler, data, fromNode, length);

      } catch (Exception e) {
        if (running) {
          LOGGER.warning("Error in receive loop: " + e.getMessage());
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private <T> void processMessage(ChannelHandler<T> handler, byte[] data, int fromNode, int length)
      throws GeneralSecurityException {
    byte[] payload = new byte[length];
    System.arraycopy(data, 8, payload, 0, length);

    if (handler.channel() != Channel.KEY_EXCHANGE) {
      byte[] key = keyManager.sessionKeys.get(new NodeId(fromNode));
      if (key == null) return;
      payload = Crypto.decrypt(key, payload);
    }

    T msg = handler.channel().pickler().deserialize(payload);
    handler.handler().accept(msg);
  }

  @Override
  public void close() {
    running = false;
    if (receiver != null) {
      receiver.interrupt();
      receiver = null;
    }
    socket.close();
  }
}
