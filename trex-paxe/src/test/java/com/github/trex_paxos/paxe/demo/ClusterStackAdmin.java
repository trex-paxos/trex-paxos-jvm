// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe.demo;

import com.github.trex_paxos.paxe.ClusterKeyManager;
import com.github.trex_paxos.paxe.Identity;
import com.github.trex_paxos.paxe.provision.BootstrapPsk;
import com.github.trex_paxos.paxe.provision.ClusterPskProvisionerClient;
import com.github.trex_paxos.paxe.provision.ClusterPskProvisionerServer;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

public class ClusterStackAdmin implements AutoCloseable {
  static final String SECRET_MAP = "secret";
  static final String BOOTSTRAP_MAP = "bootstrap";
  static final String NETWORK_MAP = "network";

  private static final String USAGE = """
      Manage cluster membership, bootstrap PSK, and cluster PSK material.
      Usage: ClusterStackAdmin -i <id@cluster> <command> [args...]
      Options:
        -i/--identity <id@cluster>  Cluster node to modify (required)
                                    | Example: 1@us.west.test
      Commands:
        init                  Generate and store a 32-byte cluster PSK for this node
        init-bootstrap        Generate and store a bootstrap PSK for TLS provisioning
        set-psk <hex>         Install the shared cluster PSK manually (64 hex chars, local dev)
        set-bootstrap <hex>   Install the bootstrap PSK manually (64 hex chars, local dev)
        print-psk             Print the locally stored cluster PSK as hex
        print-bootstrap       Print the locally stored bootstrap PSK as hex
        provision <host:port> [epoch]
                              Fetch and install the cluster PSK over TLS 1.3 provisioning
        serve-provision <host:port> [epoch]
                              Serve the cluster PSK over TLS 1.3 provisioning
        update <remote> <address>   Set network address for another node
                                    | remote: id@cluster (example 2@us.west.test)
                                    | address: host:port (example 192.0.2.0:8080)
        list                  List all configured network addresses
      """;

  private final MVStore store;
  private final MVMap<String, String> secretMap;
  private final MVMap<String, String> bootstrapMap;
  private final MVMap<String, String> networkMap;
  private final Identity identity;

  private ClusterStackAdmin(Identity identity) {
    this.identity = identity;
    String dbPath = "%s_%s.db".formatted(identity.nodeId(), identity.cluster());
    this.store = new MVStore.Builder().fileName(dbPath).open();
    this.secretMap = store.openMap(SECRET_MAP);
    this.bootstrapMap = store.openMap(BOOTSTRAP_MAP);
    this.networkMap = store.openMap(NETWORK_MAP);
  }

  void init() {
    if (!secretMap.isEmpty()) {
      var existingId = Identity.from(secretMap.keySet().iterator().next());
      if (!identity.full().equals(existingId.full())) {
        throw new IllegalStateException(
            "Store already initialized with different identity: " + existingId.full());
      }
      System.out.println("Store already initialized for " + identity.full());
      return;
    }

    byte[] psk = new byte[ClusterKeyManager.CLUSTER_PSK_SIZE];
    new SecureRandom().nextBytes(psk);
    secretMap.put(identity.full(), HexFormat.of().formatHex(psk));
    store.commit();

    System.out.printf("Node initialized. Cluster PSK generated for %s.%n", identity.full());
    System.out.printf("Run serve-provision to distribute it, or print-psk for local dev copy/paste.%n");
  }

  void initBootstrap() {
    if (bootstrapMap.containsKey(identity.cluster())) {
      System.out.println("Bootstrap PSK already initialized for cluster " + identity.cluster());
      return;
    }

    byte[] bootstrap = new byte[BootstrapPsk.KEY_SIZE];
    new SecureRandom().nextBytes(bootstrap);
    bootstrapMap.put(identity.cluster(), HexFormat.of().formatHex(bootstrap));
    store.commit();
    System.out.printf("Bootstrap PSK generated for cluster %s. Share with all members for TLS provisioning.%n",
        identity.cluster());
  }

  void setPsk(String hex) {
    if (hex.length() != ClusterKeyManager.CLUSTER_PSK_SIZE * 2) {
      throw new IllegalArgumentException("Cluster PSK must be 64 hex characters");
    }
    secretMap.put(identity.full(), hex.toLowerCase());
    store.commit();
    System.out.println("Installed cluster PSK for " + identity.full());
  }

  void setBootstrap(String hex) {
    if (hex.length() != BootstrapPsk.KEY_SIZE * 2) {
      throw new IllegalArgumentException("Bootstrap PSK must be 64 hex characters");
    }
    bootstrapMap.put(identity.cluster(), hex.toLowerCase());
    store.commit();
    System.out.println("Installed bootstrap PSK for cluster " + identity.cluster());
  }

  void printPsk() {
    var psk = secretMap.get(identity.full());
    if (psk == null) {
      throw new IllegalStateException("No cluster PSK found for self. Has node been initialized?");
    }
    System.out.println(psk);
  }

  void printBootstrap() {
    var bootstrap = bootstrapMap.get(identity.cluster());
    if (bootstrap == null) {
      throw new IllegalStateException("No bootstrap PSK found. Run init-bootstrap first.");
    }
    System.out.println(bootstrap);
  }

  void provision(String hostPort, byte epoch) throws Exception {
    var bootstrap = loadBootstrapPsk();
    var address = parseAddress(hostPort);
    byte[] clusterPsk = ClusterPskProvisionerClient.fetchClusterPsk(bootstrap, address, epoch);
    secretMap.put(identity.full(), HexFormat.of().formatHex(clusterPsk));
    store.commit();
    System.out.printf("Provisioned cluster PSK for epoch %d from %s%n", epoch & 0xFF, hostPort);
  }

  void serveProvision(String hostPort, byte epoch) throws Exception {
    var bootstrap = loadBootstrapPsk();
    byte[] clusterPsk = HexFormat.of().parseHex(requireClusterPskHex());
    var address = parseAddress(hostPort);

    try (ClusterPskProvisionerServer server = new ClusterPskProvisionerServer(
        bootstrap,
        requestedEpoch -> requestedEpoch == epoch ? clusterPsk.clone() : null,
        address)) {
      System.out.printf("Serving cluster PSK for epoch %d on %s:%d (Ctrl+C to stop)%n",
          epoch & 0xFF, address.getHostString(), server.port());
      Thread.currentThread().join();
    }
  }

  void setNodeAddress(String targetId, String address) {
    var target = Identity.from(targetId);
    validateTarget(target);
    networkMap.put(target.full(), address);
    store.commit();
    System.out.println("Set address for " + target.full() + " to " + address);
  }

  void listAddresses() {
    if (networkMap.isEmpty()) {
      System.out.println("No addresses configured");
      return;
    }
    System.out.println("Network addresses:");
    networkMap.forEach((id, address) ->
        System.out.printf("%s -> %s%n", id, address));
  }

  public boolean isSameCluster(Identity other, Identity self) {
    return other.cluster().equals(self.cluster());
  }

  private BootstrapPsk loadBootstrapPsk() {
    var hex = bootstrapMap.get(identity.cluster());
    if (hex == null) {
      throw new IllegalStateException("No bootstrap PSK found. Run init-bootstrap or set-bootstrap first.");
    }
    return BootstrapPsk.fromHex(identity.cluster(), hex);
  }

  private String requireClusterPskHex() {
    var psk = secretMap.get(identity.full());
    if (psk == null) {
      throw new IllegalStateException("No cluster PSK found. Run init or set-psk first.");
    }
    return psk;
  }

  private static InetSocketAddress parseAddress(String hostPort) {
    int separator = hostPort.lastIndexOf(':');
    if (separator <= 0 || separator == hostPort.length() - 1) {
      throw new IllegalArgumentException("Address must be host:port");
    }
    return new InetSocketAddress(hostPort.substring(0, separator), Integer.parseInt(hostPort.substring(separator + 1)));
  }

  private void validateTarget(Identity target) {
    if (target.full().equals(identity.full())) {
      throw new IllegalArgumentException("Cannot operate on self");
    }
    if (!isSameCluster(target, identity)) {
      throw new IllegalArgumentException(
          "Target cluster " + target.cluster() +
              " doesn't match store cluster " + identity.cluster());
    }
  }

  @Override
  public void close() {
    store.close();
  }

  public static void main(String[] args) {
    if (args.length < 3 || (!args[0].equals("-i") && !args[0].equals("--identity"))) {
      System.err.println(USAGE);
      System.exit(1);
    }

    try {
      var identity = Identity.from(args[1]);
      var command = args[2];
      var cmdArgs = List.of(args).subList(3, args.length);

      try (var admin = new ClusterStackAdmin(identity)) {
        switch (command) {
          case "init" -> admin.init();
          case "init-bootstrap" -> admin.initBootstrap();
          case "set-psk" -> {
            if (cmdArgs.size() != 1) {
              throw new IllegalArgumentException("set-psk requires one hex argument");
            }
            admin.setPsk(cmdArgs.get(0));
          }
          case "set-bootstrap" -> {
            if (cmdArgs.size() != 1) {
              throw new IllegalArgumentException("set-bootstrap requires one hex argument");
            }
            admin.setBootstrap(cmdArgs.get(0));
          }
          case "print-psk" -> admin.printPsk();
          case "print-bootstrap" -> admin.printBootstrap();
          case "provision" -> {
            if (cmdArgs.isEmpty() || cmdArgs.size() > 2) {
              throw new IllegalArgumentException("provision requires host:port and optional epoch");
            }
            byte epoch = cmdArgs.size() == 2 ? parseEpoch(cmdArgs.get(1)) : 0;
            admin.provision(cmdArgs.get(0), epoch);
          }
          case "serve-provision" -> {
            if (cmdArgs.isEmpty() || cmdArgs.size() > 2) {
              throw new IllegalArgumentException("serve-provision requires host:port and optional epoch");
            }
            byte epoch = cmdArgs.size() == 2 ? parseEpoch(cmdArgs.get(1)) : 0;
            admin.serveProvision(cmdArgs.get(0), epoch);
          }
          case "update" -> {
            if (cmdArgs.size() != 2) {
              throw new IllegalArgumentException("update requires target and host:port");
            }
            admin.setNodeAddress(cmdArgs.get(0), cmdArgs.get(1));
          }
          case "list" -> admin.listAddresses();
          default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
      }
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      System.exit(1);
    }
  }

  private static byte parseEpoch(String value) {
    int epoch = Integer.parseInt(value);
    if (epoch < 0 || epoch > 255) {
      throw new IllegalArgumentException("Epoch must be between 0 and 255");
    }
    return (byte) epoch;
  }
}