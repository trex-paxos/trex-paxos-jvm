package com.github.trex_paxos.paxe;

import com.github.trex_paxos.network.*;
import java.net.DatagramSocket;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class NetworkTestHarness implements AutoCloseable {
  private static final int KEY_EXCHANGE_TIMEOUT_SECONDS = 5;
  private static final int NETWORK_STARTUP_TIMEOUT_SECONDS = 10;

  private final List<PaxeNetwork> networks = new ArrayList<>();
  private final CountDownLatch networkReady = new CountDownLatch(1);
  private final ClusterId clusterId;
  private final SRPUtils.Constants srpConstants;
  private final Map<NodeId, NetworkAddress> addressMap = new ConcurrentHashMap<>();
  private final Map<NodeId, NodeVerifier> verifierMap = new ConcurrentHashMap<>();
  private volatile boolean shutdownInitiated = false;

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
    if (shutdownInitiated) {
      throw new IllegalStateException("Cannot create network after shutdown initiated");
    }

    DatagramSocket tempSocket = new DatagramSocket(0);
    int port = tempSocket.getLocalPort();
    tempSocket.close();

    NodeId id = new NodeId(nodeId);
    NetworkAddress addr = new NetworkAddress.InetAddress("127.0.0.1", port);
    addressMap.put(id, addr);

    NodeClientSecret nodeSecret = new NodeClientSecret(
        clusterId,
        id,
        "password" + nodeId,
        SRPUtils.generateSalt()
    );

    final var verifier = SRPUtils.generateVerifier(
        srpConstants,
        nodeSecret.srpIdenity(),
        nodeSecret.password(),
        nodeSecret.salt()
    );

    final var nodeVerifier = new NodeVerifier(nodeSecret.srpIdenity(), verifier.toString(16));
    verifierMap.put(id, nodeVerifier);

    Supplier<Map<NodeId, NodeVerifier>> verifierLookup = () -> verifierMap;
    Supplier<ClusterMembership> membershipSupplier = () -> new ClusterMembership(new HashMap<>(addressMap));

    SessionKeyManager keyManager = new SessionKeyManager(id, srpConstants, nodeSecret, verifierLookup);
    PaxeNetwork network = new PaxeNetwork(keyManager, port, id, membershipSupplier);

    networks.add(network);
    return network;
  }

  public void waitForNetworkEstablishment() throws Exception {
    List<CompletableFuture<Void>> startupFutures = new ArrayList<>();

    for (PaxeNetwork network : networks) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        network.start();
        waitForKeyExchange(network);
      });
      startupFutures.add(future);
    }

    try {
      CompletableFuture.allOf(startupFutures.toArray(new CompletableFuture[0]))
          .get(NETWORK_STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      networkReady.countDown();
    } catch (TimeoutException e) {
      throw new IllegalStateException("Network establishment timed out", e);
    }
  }

  private void waitForKeyExchange(PaxeNetwork network) {
    long deadline = System.currentTimeMillis() + (KEY_EXCHANGE_TIMEOUT_SECONDS * 1000);

    while (System.currentTimeMillis() < deadline) {
      if (networks.stream()
          .filter(n -> !n.equals(network))
          .allMatch(other -> network.keyManager.sessionKeys.containsKey(other.localNode))) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Key exchange interrupted", e);
      }
    }
    throw new IllegalStateException("Key exchange timed out for node: " + network.localNode);
  }

  @Override
  public void close() {
    shutdownInitiated = true;
    networks.forEach(PaxeNetwork::close);
    networks.clear();
    addressMap.clear();
    verifierMap.clear();
  }
}
