// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe.demo;

import com.github.trex_paxos.paxe.Identity;
import com.github.trex_paxos.paxe.SRPUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.util.List;

public class ClusterStackAdmin implements AutoCloseable {
  static final String SECRET_MAP = "secret";
  static final String PASSWORD_FILE_MAP = "password_file";
  static final String NETWORK_MAP = "network";

  // RFC 5054 3072-bit parameters
  @SuppressWarnings("SpellCheckingInspection")
  static final String DEFAULT_N = """
      FFFFFFFF FFFFFFFF C90FDAA2 2168C234 C4C6628B 80DC1CD1 29024E08\
      8A67CC74 020BBEA6 3B139B22 514A0879 8E3404DD EF9519B3 CD3A431B\
      302B0A6D F25F1437 4FE1356D 6D51C245 E485B576 625E7EC6 F44C42E9\
      A637ED6B 0BFF5CB6 F406B7ED EE386BFB 5A899FA5 AE9F2411 7C4B1FE6\
      49286651 ECE45B3D C2007CB8 A163BF05 98DA4836 1C55D39A 69163FA8\
      FD24CF5F 83655D23 DCA3AD96 1C62F356 208552BB 9ED52907 7096966D\
      670C354E 4ABC9804 F1746C08 CA18217C 32905E46 2E36CE3B E39E772C\
      180E8603 9B2783A2 EC07A28F B5C55DF0 6F4C52C9 DE2BCBF6 95581718\
      3995497C EA956AE5 15D22618 98FA0510 15728E5A 8AAAC42D AD33170D\
      04507A33 A85521AB DF1CBA64 ECFB8504 58DBEF0A 8AEA7157 5D060C7D\
      B3970F85 A6E1E4C7 ABF5AE8C DB0933D7 1E8C94E0 4A25619D CEE3D226\
      1AD2EE6B F12FFA06 D98A0864 D8760273 3EC86A64 521F2B18 177B200C\
      BBE11757 7A615D6C 770988C0 BAD946E2 08E24FA0 74E5AB31 43DB5BFC\
      E0FD108E 4B82D120 A93AD2CA FFFFFFFF FFFFFFFF""".replaceAll("\\s+", "");

  static final String DEFAULT_G = "5";
  private static final String USAGE = """
      Update cluster membership and RFC 5054 SRP verifiers.
      Usage: ClusterStackAdmin -i <id@cluster> <command> [args...]
      Options:
        -i/--identity <id@cluster>  Cluster node to modify (required)
                                    | Example: 1@us.west.test
      Commands:
        init [N] [g]          Initialize a new node
                                    | N: RFC 5054 N parameter (default 3072-bit)
                                    | g: RFC 5054 g parameter (default 5)
        add <remote> <params>       Add verifier for another node
                                    | remote: id@cluster (example 2@us.west.test)
                                    | params: N,g,s,v
        update <remote> <address>   Set network address for another node
                                    | remote: id@cluster (example 2@us.west.test)
                                    | address: host:port (example 192.0.2.0:8080)
        print                       Print local node verifier details
        verifiers                   List all configured verifiers
        list                        List all configured network addresses
      """;

  private final MVStore store;
  private final MVMap<String, String> secretMap;
  private final MVMap<String, String> passwordFileMap;
  private final MVMap<String, String> networkMap;
  private final Identity identity;

  private ClusterStackAdmin(Identity identity) {
    this.identity = identity;
    String dbPath = "%s_%s.db".formatted(identity.nodeId(), identity.cluster());
    this.store = new MVStore.Builder().fileName(dbPath).open();
    this.secretMap = store.openMap(SECRET_MAP);
    this.passwordFileMap = store.openMap(PASSWORD_FILE_MAP);
    this.networkMap = store.openMap(NETWORK_MAP);
  }

  void init(String N, String g) {
    if (!secretMap.isEmpty()) {
      var existingId = Identity.from(secretMap.keySet().iterator().next());
      if (!identity.full().equals(existingId.full())) {
        throw new IllegalStateException(
            "Store already initialized with different identity: " + existingId.full());
      }
      System.out.println("Store already initialized for " + identity.full());
      return;
    }

    var salt = SRPUtils.generateSalt();
    var password = SRPUtils.generatedPrivateKey(DEFAULT_N);
    var secret = "%s,%s".formatted(SRPUtils.toHex(salt), password);
    secretMap.put(identity.full(), secret);

    var v = SRPUtils.generateVerifier(
        new SRPUtils.Constants(N, g),
        identity.full(),
        password,
        salt
    ).toString(16).toUpperCase();

    // Store own verifier details
    var selfVerifierDetails = String.format("%s,%s,%s,%s", N, g, SRPUtils.toHex(salt), v);
    passwordFileMap.put(identity.full(), selfVerifierDetails);

    store.commit();

    System.out.printf("Node initialized. Add to other nodes with:%n" +
            "add %s %s,%s,%s,%s%n",
        identity.full(), N, g, SRPUtils.toHex(salt), v);
  }


  void addNodeVerifier(String targetId, String params) {
    var target = Identity.from(targetId);
    validateTarget(target);
    passwordFileMap.put(target.full(), params);
    store.commit();
    System.out.println("Added verifier for " + target.full());
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

  void setNodeAddress(String targetId, String address) {
    var target = Identity.from(targetId);
    validateTarget(target);
    if (!passwordFileMap.containsKey(target.full())) {
      throw new IllegalStateException("No verifier found for " + target.full());
    }

    networkMap.put(target.full(), address);
    store.commit();
    System.out.println("Set address for " + target.full() + " to " + address);
  }

  void listVerifiers() {
    if (passwordFileMap.isEmpty()) {
      System.out.println("No verifiers configured");
      return;
    }
    System.out.println("Configured verifiers:");
    passwordFileMap.forEach((id, params) ->
        System.out.printf("%s -> %s%n", id, params));
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
          case "init" -> {
            var N = cmdArgs.isEmpty() ? DEFAULT_N : cmdArgs.get(0);
            var g = cmdArgs.size() > 1 ? cmdArgs.get(1) : DEFAULT_G;
            admin.init(N, g);
          }
          case "add" -> {
            if (cmdArgs.size() != 2) {
              throw new IllegalArgumentException(
                  "add requires target and N,g,s,v");
            }
            admin.addNodeVerifier(cmdArgs.get(0), cmdArgs.get(1));
          }
          case "update" -> {
            if (cmdArgs.size() != 2) {
              throw new IllegalArgumentException(
                  "update requires target and host:port");
            }
            admin.setNodeAddress(cmdArgs.get(0), cmdArgs.get(1));
          }
          case "print" -> {
            var verifier = admin.passwordFileMap.get(admin.identity.full());
            if (verifier == null) {
              throw new IllegalStateException("No verifier found for self. Has node been initialized?");
            }
            System.out.printf("add %s %s%n", admin.identity.full(), verifier);
          }
          case "verifiers" -> admin.listVerifiers();
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
