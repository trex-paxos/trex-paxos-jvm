package com.github.trex_paxos.paxe;

import com.github.trex_paxos.network.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;

public class NetworkTestHarness implements AutoCloseable {
  private static final Duration KEY_EXCHANGE_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration CHANNEL_SELECT_TIMEOUT = Duration.ofMillis(250);

  private final List<PaxeNetwork> networks = new ArrayList<>();
  private final ClusterId clusterId;
  private final SRPUtils.Constants srpConstants;
  private final Map<NodeId, NetworkAddress> addressMap = new HashMap<>();
  private final Map<NodeId, NodeVerifier> verifierMap = new HashMap<>();
  private volatile boolean closed;

  public NetworkTestHarness() {
    this(new ClusterId("test.cluster"), new SRPUtils.Constants(
        "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" +
            "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" +
            "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" +
            "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" +
            "FD5138FE8376435B9FC61D2FC0EB06E3",
        "2"
    ));
  }

  public NetworkTestHarness(ClusterId clusterId, SRPUtils.Constants srpConstants) {
    this.clusterId = clusterId;
    this.srpConstants = srpConstants;
  }

  public PaxeNetwork createNetwork(short nodeId) throws Exception {
    if (closed) throw new IllegalStateException("Harness is closed");

    // Get ephemeral port from channel
    DatagramChannel tempChannel = DatagramChannel.open();
    tempChannel.socket().bind(new InetSocketAddress(0));
    int port = tempChannel.socket().getLocalPort();
    tempChannel.close();

    NodeId id = new NodeId(nodeId);
    NetworkAddress addr = new NetworkAddress.InetAddress("127.0.0.1", port);
    addressMap.put(id, addr);

    // Setup SRP authentication
    NodeClientSecret nodeSecret = new NodeClientSecret(
        clusterId,
        id,
        "password" + nodeId,
        SRPUtils.generateSalt()
    );

    NodeVerifier verifier = createVerifier(nodeSecret);
    verifierMap.put(id, verifier);

    Supplier<Map<NodeId, NodeVerifier>> verifierLookup = () -> verifierMap;
    Supplier<ClusterMembership> membershipSupplier = () ->
        new ClusterMembership(new HashMap<>(addressMap));

    SessionKeyManager keyManager = new SessionKeyManager(
        id,
        srpConstants,
        nodeSecret,
        verifierLookup
    );

    PaxeNetwork network = new PaxeNetwork(keyManager, port, id, membershipSupplier);
    networks.add(network);
    return network;
  }

  public void waitForNetworkEstablishment() throws Exception {
    List<CompletableFuture<Void>> startupFutures = new ArrayList<>();

    for (PaxeNetwork network : networks) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          network.start();
          waitForKeyExchange(network);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      startupFutures.add(future);
    }

    try {
      CompletableFuture.allOf(startupFutures.toArray(new CompletableFuture[0]))
          .get(KEY_EXCHANGE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (Exception e) {
      networks.forEach(PaxeNetwork::close);
      throw e;
    }
  }

  private NodeVerifier createVerifier(NodeClientSecret secret) {
    final var v = SRPUtils.generateVerifier(
        srpConstants,
        secret.srpIdentity(),
        secret.password(),
        secret.salt()
    );
    return new NodeVerifier(secret.srpIdentity(), v.toString(16));
  }

  private void waitForKeyExchange(PaxeNetwork network) {
    long deadline = System.currentTimeMillis() + KEY_EXCHANGE_TIMEOUT.toMillis();
    try (Selector selector = Selector.open()) {
      network.channel.register(selector, SelectionKey.OP_READ);

      while (System.currentTimeMillis() < deadline) {
        if (selector.select(CHANNEL_SELECT_TIMEOUT.toMillis()) > 0) {
          selector.selectedKeys().clear();
        }

        // Check if key exchange complete with all peers
        if (networks.stream()
            .filter(n -> !n.equals(network))
            .allMatch(other -> network.keyManager.sessionKeys.containsKey(other.localNode))) {
          return;
        }
      }
      throw new IllegalStateException("Key exchange timed out for node: " + network.localNode);
    } catch (IOException e) {
      throw new RuntimeException("Selector error during key exchange", e);
    }
  }

  @Override
  public void close() {
    closed = true;
    networks.forEach(PaxeNetwork::close);
    networks.clear();
    addressMap.clear();
    verifierMap.clear();
  }
}
