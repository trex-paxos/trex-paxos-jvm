package com.github.trex_paxos.paxe;

import com.github.trex_paxos.network.ClusterMembership;
import com.github.trex_paxos.network.NetworkAddress;
import com.github.trex_paxos.network.NodeId;

import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.github.trex_paxos.network.SystemChannel.KEY_EXCHANGE;
import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;

record NetworkWithTempPort(PaxeNetwork network, int port) {
}

public class NetworkTestHarness implements AutoCloseable {
  private static final Duration KEY_EXCHANGE_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration CHANNEL_SELECT_TIMEOUT = Duration.ofMillis(100);

  private final List<PaxeNetwork> networks = new ArrayList<>();
  private final ClusterId clusterId;
  private final SRPUtils.Constants srpConstants;
  private final Map<NodeId, NetworkAddress> addressMap = new HashMap<>();
  private final Map<NodeId, NodeVerifier> verifierMap = new HashMap<>();
  private volatile boolean closed;

  public NetworkTestHarness() {
    //noinspection SpellCheckingInspection
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

  NetworkWithTempPort createNetwork(short nodeId) throws Exception {
    if (closed) {
      LOGGER.warning("Attempt to create network after harness closed");
      throw new IllegalStateException("Harness is closed");
    }

    LOGGER.fine(() -> String.format("Creating network node %d", nodeId));
    DatagramChannel tempChannel = DatagramChannel.open();
    tempChannel.socket().bind(new InetSocketAddress(0));
    int port = tempChannel.socket().getLocalPort();
    tempChannel.close();
    LOGGER.fine(() -> String.format("Allocated port %d for node %d", port, nodeId));

    NodeId id = new NodeId(nodeId);
    NetworkAddress address = new NetworkAddress("127.0.0.1", port);
    addressMap.put(id, address);

    NodeClientSecret nodeSecret = new NodeClientSecret(
        clusterId,
        id,
        "password" + nodeId,
        SRPUtils.generateSalt()
    );
    LOGGER.finest(() -> String.format("Created node secret for %d: %s", nodeId, nodeSecret.srpIdentity()));

    NodeVerifier verifier = createVerifier(nodeSecret);
    verifierMap.put(id, verifier);
    LOGGER.finest(() -> String.format("Generated verifier for %d", nodeId));

    Supplier<Map<NodeId, NodeVerifier>> verifierLookup = () -> verifierMap;
    Supplier<ClusterMembership> membershipSupplier = () ->
        new ClusterMembership(new HashMap<>(addressMap));

    SessionKeyManager keyManager = new SessionKeyManager(
        id,
        srpConstants,
        nodeSecret,
        verifierLookup
    );

    PaxeNetwork network = new PaxeNetwork.Builder(keyManager, port, id, membershipSupplier).build();
    networks.add(network);
    LOGGER.fine(() -> String.format("Network node %d created successfully", nodeId));
    return new NetworkWithTempPort(network, port);
  }

  public void waitForNetworkEstablishment() throws Exception {
    LOGGER.fine("Starting network establishment wait");
    List<CompletableFuture<Void>> startupFutures = new ArrayList<>();

    for (PaxeNetwork network : networks) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          LOGGER.fine(() -> String.format("Starting network node %d", network.localNode.id()));
          network.start();
          waitForKeyExchange(network);
        } catch (Exception e) {
          LOGGER.warning(() -> String.format("Network node %d startup failed: %s",
              network.localNode.id(), e.getMessage()));
          throw new RuntimeException(e);
        }
      });
      startupFutures.add(future);
    }

    try {
      CompletableFuture.allOf(startupFutures.toArray(new CompletableFuture[0]))
          .get(KEY_EXCHANGE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      LOGGER.fine("Network establishment completed successfully");
    } catch (Exception e) {
      LOGGER.warning("Network establishment failed, closing networks: " + e);
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
    LOGGER.fine(() -> String.format("Waiting for key exchange on node %d", network.localNode.id()));
    long deadline = System.currentTimeMillis() + KEY_EXCHANGE_TIMEOUT.toMillis();
    // Trigger key exchange with all other nodes
    networks.stream()
        .filter(n -> !n.equals(network))
        .forEach(peer -> {
          LOGGER.finest(() -> String.format("Node %d initiating key exchange with %d",
              network.localNode.id(), peer.localNode.id()));
          var msg = network.keyManager.initiateHandshake(peer.localNode);
          msg.ifPresent(keyMessage -> network.send(KEY_EXCHANGE.value(), peer.localNode, keyMessage));
        });
    AtomicInteger attempts = new AtomicInteger();
    while (System.currentTimeMillis() < deadline) {
      LOGGER.finest(() -> String.format("Key exchange check attempt %d for node %d",
          attempts.getAndIncrement(), network.localNode.id()));

      boolean exchangeComplete = networks.stream()
          .filter(n -> !n.equals(network))
          .allMatch(other -> {
            boolean hasKey = network.keyManager.sessionKeys.containsKey(other.localNode);
            LOGGER.finest(() -> String.format("Node %d has%s key for %d",
                network.localNode.id(), hasKey ? "" : " no", other.localNode.id()));
            return hasKey;
          });

      if (exchangeComplete) {
        LOGGER.fine(() -> String.format("Key exchange completed for node %d after %d attempts",
            network.localNode.id(), attempts.getAndIncrement()));
        return;
      }

      try {
        //noinspection BusyWait
        Thread.sleep(CHANNEL_SELECT_TIMEOUT.toMillis());
      } catch (InterruptedException e) {
        LOGGER.warning(() -> String.format("Key exchange wait interrupted for node %d",
            network.localNode.id()));
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }

    LOGGER.warning(() -> String.format("Key exchange timed out for node %d after %d attempts",
        network.localNode.id(), attempts.get()));
    throw new IllegalStateException("Key exchange timed out for node: " + network.localNode);
  }

  @Override
  public void close() {
    LOGGER.fine("Closing test harness");
    closed = true;
    networks.forEach(PaxeNetwork::close);
    networks.clear();
    addressMap.clear();
    verifierMap.clear();
    LOGGER.fine("Test harness closed");
  }
}
