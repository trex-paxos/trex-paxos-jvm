// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe.demo;

import com.github.trex_paxos.paxe.ClusterKeyManager;
import com.github.trex_paxos.paxe.Identity;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

public class ClusterStackAdmin implements AutoCloseable {
  static final String SECRET_MAP = "secret";
  static final String NETWORK_MAP = "network";

  private static final String USAGE = """
      Manage cluster membership and cluster PSK material.
      Usage: ClusterStackAdmin -i <id@cluster> <command> [args...]
      Options:
        -i/--identity <id@cluster>  Cluster node to modify (required)
                                    | Example: 1@us.west.test
      Commands:
        init                  Generate and store a 32-byte cluster PSK for this node
        set-psk <hex>         Install the shared cluster PSK (64 hex chars)
        print-psk             Print the locally stored cluster PSK as hex
        update <remote> <address>   Set network address for another node
                                    | remote: id@cluster (example 2@us.west.test)
                                    | address: host:port (example 192.0.2.0:8080)
        list                  List all configured network addresses
      """;

  private final MVStore store;
  private final MVMap<String, String> secretMap;
  private final MVMap<String, String> networkMap;
  private final Identity identity;

  private ClusterStackAdmin(Identity identity) {
    this.identity = identity;
    String dbPath = "%s_%s.db".formatted(identity.nodeId(), identity.cluster());
    this.store = new MVStore.Builder().fileName(dbPath).open();
    this.secretMap = store.openMap(SECRET_MAP);
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

    System.out.printf("Node initialized. Share this cluster PSK with all members:%n%s%n", HexFormat.of().formatHex(psk));
  }

  void setPsk(String hex) {
    if (hex.length() != ClusterKeyManager.CLUSTER_PSK_SIZE * 2) {
      throw new IllegalArgumentException("Cluster PSK must be 64 hex characters");
    }
    secretMap.put(identity.full(), hex.toLowerCase());
    store.commit();
    System.out.println("Installed cluster PSK for " + identity.full());
  }

  void printPsk() {
    var psk = secretMap.get(identity.full());
    if (psk == null) {
      throw new IllegalStateException("No cluster PSK found for self. Has node been initialized?");
    }
    System.out.println(psk);
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
          case "set-psk" -> {
            if (cmdArgs.size() != 1) {
              throw new IllegalArgumentException("set-psk requires one hex argument");
            }
            admin.setPsk(cmdArgs.get(0));
          }
          case "print-psk" -> admin.printPsk();
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
}
