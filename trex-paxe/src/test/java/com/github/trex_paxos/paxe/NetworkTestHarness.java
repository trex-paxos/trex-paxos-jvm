// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.network.NodeEndpoints;
import com.github.trex_paxos.network.NetworkAddress;
import com.github.trex_paxos.NodeId;

import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;

public class NetworkTestHarness implements AutoCloseable {
  private static final long STARTUP_TIMEOUT_SECONDS = 2;

  private final List<PaxeNetwork> networks = new ArrayList<>();
  private final byte[] clusterPsk;
  private final Map<NodeId, NetworkAddress> addressMap = new HashMap<>();
  private volatile boolean closed;

  public NetworkTestHarness() {
    this(generateClusterPsk());
  }

  public NetworkTestHarness(byte[] clusterPsk) {
    if (clusterPsk.length != ClusterKeyManager.CLUSTER_PSK_SIZE) {
      throw new IllegalArgumentException("Cluster PSK must be " + ClusterKeyManager.CLUSTER_PSK_SIZE + " bytes");
    }
    this.clusterPsk = clusterPsk.clone();
  }

  public static byte[] generateClusterPsk() {
    byte[] psk = new byte[ClusterKeyManager.CLUSTER_PSK_SIZE];
    new SecureRandom().nextBytes(psk);
    return psk;
  }

  public NetworkWithTempPort createNetwork(short nodeId) throws Exception {
    if (closed) {
      LOGGER.warning("Attempt to create network after harness closed");
      throw new IllegalStateException("Harness is closed");
    }

    LOGGER.fine(() -> String.format("Creating network node %d", nodeId));
    DatagramChannel tempChannel = DatagramChannel.open();
    tempChannel.socket().bind(new InetSocketAddress(0));
    int port = tempChannel.socket().getLocalPort();
    tempChannel.close();

    NodeId id = new NodeId(nodeId);
    addressMap.put(id, new NetworkAddress("127.0.0.1", port));

    ClusterKeyManager keyManager = new ClusterKeyManager(clusterPsk);
    Supplier<NodeEndpoints> membershipSupplier = () -> new NodeEndpoints(new HashMap<>(addressMap));

    PaxeNetwork network = new PaxeNetwork.Builder(keyManager, port, id, membershipSupplier).build();
    networks.add(network);
    return new NetworkWithTempPort(network, port);
  }

  public void waitForNetworkEstablishment() throws Exception {
    LOGGER.fine("Starting network nodes");
    List<CompletableFuture<Void>> startupFutures = networks.stream()
        .map(network -> CompletableFuture.runAsync(network::start))
        .toList();

    CompletableFuture.allOf(startupFutures.toArray(CompletableFuture[]::new))
        .get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    LOGGER.fine("All network nodes started");
  }

  @Override
  public void close() {
    LOGGER.fine("Closing test harness");
    closed = true;
    networks.forEach(PaxeNetwork::close);
    networks.clear();
    addressMap.clear();
  }
}
