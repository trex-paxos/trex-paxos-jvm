package com.github.trex_paxos.paxe;

import com.github.trex_paxos.Pickle;
import com.github.trex_paxos.Pickler;
import com.github.trex_paxos.network.*;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;

public final class PaxeNetwork implements NetworkLayer {
  final SessionKeyManager keyManager;
  final NodeId localNode;
  private final DatagramSocket socket;
  private final ByteBuffer receiveBuffer;
  private final Map<Integer, ChannelHandler<?>> handlers;
  final Supplier<ClusterMembership> membership;

  volatile boolean running;
  private Thread receiver;

  private record ChannelHandler<T>(Channel channel, Consumer<T> handler) {}

  private final Map<Channel,Pickler<?>> picklerMap = Map.of(
      Channel.CONSENSUS, PickleMsg.instance,
      Channel.PROXY, Pickle.instance,
      Channel.KEY_EXCHANGE, Pickle.instance);

  public PaxeNetwork(SessionKeyManager keyManager, int port, NodeId local, Supplier<ClusterMembership> membership) throws IOException {
    this.keyManager = keyManager;
    this.localNode = local;
    this.socket = new DatagramSocket(port);
    this.receiveBuffer = ByteBuffer.allocateDirect(65535);
    this.handlers = new ConcurrentHashMap<>();
    this.membership = membership;
  }

  public <T> void subscribe(Channel channel, Consumer<T> handler) {
    handlers.put((int)channel.id(), new ChannelHandler<>(channel, handler));
  }

  @Override
  public <T> void send(Channel channel, NodeId to, T msg) {

    Pickler<T> pickler = (Pickler<T>) picklerMap.get(channel);
    byte[] payload = pickler.serialize(msg);
    NetworkAddress address = membership.get().addressFor(to).orElseThrow();
    byte[] header = PaxeHeader.toBytes(localNode.id(), to.id(), channel.id(), payload.length);

    if (channel == Channel.KEY_EXCHANGE) {
      try {
        sendDatagram(address, header, payload);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    } else {
      byte[] key = keyManager.sessionKeys.get(to);
      if (key == null) {
        keyManager.initiateHandshake(to);
        return;
      }
      byte[] encrypted = null;
      try {
        encrypted = encrypt(key, payload);
      } catch (GeneralSecurityException e) {
        throw new RuntimeException(e);
      }
      try {
        sendDatagram(address, header, encrypted);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  public <T> void broadcast(Channel channel, T msg) throws IOException, GeneralSecurityException {
    for (NodeId node : membership.get().otherNodes(localNode)) {
      send(channel, node, msg);
    }
  }

  private void sendDatagram(NetworkAddress address, byte[] header, byte[] payload) throws IOException {
    byte[] combined = new byte[header.length + payload.length];
    System.arraycopy(header, 0, combined, 0, header.length);
    System.arraycopy(payload, 0, combined, header.length, payload.length);

    DatagramPacket packet = new DatagramPacket(
        combined, combined.length,
        InetAddress.getByName(address.hostString()),
        address.port()
    );
    socket.send(packet);
  }

  @Override
  public <T> void subscribe(Channel channel, Consumer<T> handler, String name) {

  }

  @Override
  public <T> void broadcast(Supplier<ClusterMembership> membershipSupplier, Channel channel, T msg) {

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
      byte[] key = keyManager.sessionKeys.get(new NodeId((short) fromNode));
      if (key == null) return;
      payload = decrypt(key, payload);
    }

    Pickler<T> pickler = (Pickler<T>) picklerMap.get(handler.channel());
    T msg = pickler.deserialize(payload);
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

  private byte[] encrypt(byte[] key, byte[] data) throws GeneralSecurityException {
    var nonce = new byte[12];
    ThreadLocalRandom.current().nextBytes(nonce);

    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    var gcmSpec = new GCMParameterSpec(128, nonce);
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), gcmSpec);

    var ciphertext = cipher.doFinal(data);
    var result = new byte[nonce.length + ciphertext.length];
    System.arraycopy(nonce, 0, result, 0, nonce.length);
    System.arraycopy(ciphertext, 0, result, nonce.length, ciphertext.length);

    return result;
  }

  private byte[] decrypt(byte[] key, byte[] data) throws GeneralSecurityException {
    var nonce = new byte[12];
    System.arraycopy(data, 0, nonce, 0, nonce.length);

    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    var gcmSpec = new GCMParameterSpec(128, nonce);
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), gcmSpec);

    var ciphertext = new byte[data.length - nonce.length];
    System.arraycopy(data, nonce.length, ciphertext, 0, ciphertext.length);

    return cipher.doFinal(ciphertext);
  }
}
