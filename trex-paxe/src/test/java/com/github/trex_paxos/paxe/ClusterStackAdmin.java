package com.github.trex_paxos.paxe;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.util.Arrays;
import java.util.Optional;

public class ClusterStackAdmin implements AutoCloseable {
  static final String SECRET_MAP = "secret";
  static final String PASSWORD_FILE_MAP = "password_file";
  static final String NETWORK_MAP = "network";

  // Using RFC 5054 3072-bit parameters for stronger security
  static final String DEFAULT_N =
      "FFFFFFFF FFFFFFFF C90FDAA2 2168C234 C4C6628B 80DC1CD1 29024E08" +
          "8A67CC74 020BBEA6 3B139B22 514A0879 8E3404DD EF9519B3 CD3A431B" +
          "302B0A6D F25F1437 4FE1356D 6D51C245 E485B576 625E7EC6 F44C42E9" +
          "A637ED6B 0BFF5CB6 F406B7ED EE386BFB 5A899FA5 AE9F2411 7C4B1FE6" +
          "49286651 ECE45B3D C2007CB8 A163BF05 98DA4836 1C55D39A 69163FA8" +
          "FD24CF5F 83655D23 DCA3AD96 1C62F356 208552BB 9ED52907 7096966D" +
          "670C354E 4ABC9804 F1746C08 CA18217C 32905E46 2E36CE3B E39E772C" +
          "180E8603 9B2783A2 EC07A28F B5C55DF0 6F4C52C9 DE2BCBF6 95581718" +
          "3995497C EA956AE5 15D22618 98FA0510 15728E5A 8AAAC42D AD33170D" +
          "04507A33 A85521AB DF1CBA64 ECFB8504 58DBEF0A 8AEA7157 5D060C7D" +
          "B3970F85 A6E1E4C7 ABF5AE8C DB0933D7 1E8C94E0 4A25619D CEE3D226" +
          "1AD2EE6B F12FFA06 D98A0864 D8760273 3EC86A64 521F2B18 177B200C" +
          "BBE11757 7A615D6C 770988C0 BAD946E2 08E24FA0 74E5AB31 43DB5BFC" +
          "E0FD108E 4B82D120 A93AD2CA FFFFFFFF FFFFFFFF".replaceAll("\\s+", "");

  static final String DEFAULT_G = "5";

  final MVStore store;
  final MVMap<String, String> secretMap;
  final MVMap<String, String> passwordFileMap;
  final MVMap<String, String> networkMap;

  record Identity(short nodeId, String cluster) {
    String full() {
      return nodeId + "@" + cluster;
    }

    static Identity from(String id) {
      String[] parts = id.split("@", 2);
      if (parts.length != 2) {
        throw new IllegalArgumentException("Identity must be in format nodeId@cluster");
      }
      try {
        return new Identity(Short.parseShort(parts[0]), parts[1]);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid nodeId: " + parts[0]);
      }
    }

    boolean sameCluster(Identity other) {
      return cluster.equals(other.cluster);
    }
  }

  ClusterStackAdmin(String filename) {
    store = new MVStore.Builder().fileName(filename).open();
    secretMap = store.openMap(SECRET_MAP);
    passwordFileMap = store.openMap(PASSWORD_FILE_MAP);
    networkMap = store.openMap(NETWORK_MAP);
  }

  void initialize(String id, Optional<String> nParam, Optional<String> gParam) {
    var identity = Identity.from(id);
    if (!secretMap.isEmpty()) {
      var existingId = Identity.from(secretMap.keySet().iterator().next());
      if (!identity.full().equals(existingId.full())) {
        throw new IllegalArgumentException(
            "Store already initialized with identity: " + existingId.full());
      }
      System.out.println("Store already initialized for " + identity.full());
      return;
    }

    var salt = SRPUtils.generateSalt();
    var password = SRPUtils.generatedPrivateKey(DEFAULT_N);
    var secret = String.format("%s,%s",
        SRPUtils.toHex(salt),
        password);
    secretMap.put(identity.full(), secret);

    var N = nParam.orElse(DEFAULT_N);
    var g = gParam.orElse(DEFAULT_G);
    var v = SRPUtils.generateVerifier(
        new SRPUtils.Constants(N, g),
        identity.full(),
        password,
        salt
    ).toString(16).toUpperCase();

    store.commit();

    // Output format for add_node_verifier command
    System.out.printf("Node initialized. Add to other nodes with:%n" +
            "add_node_verifier %s %s,%s,%s,%s%n",
        identity.full(), N, g, SRPUtils.toHex(salt), v);
  }

  void addNodeVerifier(String id, String params) {
    validateIdentity();
    var targetId = Identity.from(id);
    var currentId = getCurrentIdentity();

    if (targetId.full().equals(currentId.full())) {
      throw new IllegalArgumentException("Cannot add verifier for self");
    }
    if (!targetId.sameCluster(currentId)) {
      throw new IllegalArgumentException(
          "Target cluster " + targetId.cluster +
              " doesn't match store cluster " + currentId.cluster);
    }

    passwordFileMap.put(targetId.full(), params);
    store.commit();
    System.out.println("Added verifier for " + targetId.full());
  }

  void setNodeAddress(String id, String address) {
    validateIdentity();
    var targetId = Identity.from(id);
    var currentId = getCurrentIdentity();

    if (targetId.full().equals(currentId.full())) {
      throw new IllegalArgumentException("Cannot set address for self");
    }
    if (!targetId.sameCluster(currentId)) {
      throw new IllegalArgumentException(
          "Target cluster " + targetId.cluster +
              " doesn't match store cluster " + currentId.cluster);
    }
    if (!passwordFileMap.containsKey(targetId.full())) {
      throw new IllegalArgumentException(
          "No verifier found for " + targetId.full());
    }

    networkMap.put(targetId.full(), address);
    store.commit();
    System.out.println("Set address for " + targetId.full() + " to " + address);
  }

  void listVerifiers() {
    validateIdentity();
    if (passwordFileMap.isEmpty()) {
      System.out.println("No verifiers configured");
      return;
    }
    System.out.println("Configured verifiers:");
    passwordFileMap.forEach((id, params) ->
        System.out.printf("%s -> %s%n", id, params));
  }

  void listAddresses() {
    validateIdentity();
    if (networkMap.isEmpty()) {
      System.out.println("No addresses configured");
      return;
    }
    System.out.println("Network addresses:");
    networkMap.forEach((id, addr) ->
        System.out.printf("%s -> %s%n", id, addr));
  }

  private Identity getCurrentIdentity() {
    if (secretMap.isEmpty()) {
      throw new IllegalStateException("Store not initialized");
    }
    return Identity.from(secretMap.keySet().iterator().next());
  }

  private void validateIdentity() {
    if (secretMap.isEmpty()) {
      throw new IllegalStateException("Store not initialized");
    }
  }

  public void close() {
    store.close();
  }

  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("Usage: " + ClusterStackAdmin.class.getName() + " <store.db> <command> [args...]");
      System.err.println("Commands:");
      System.err.println("  initialize <nodeId@cluster> [N] [g]");
      System.err.println("  add_node_verifier <nodeId@cluster> <N,g,s,v>");
      System.err.println("  set_node_address <nodeId@cluster> <host:port>");
      System.err.println("  list_verifiers");
      System.err.println("  list_addresses");
      System.exit(1);
    }

    try (var admin = new ClusterStackAdmin(args[0])) {
      var cmd = args[1];
      var cmdArgs = Arrays.copyOfRange(args, 2, args.length);

      switch (cmd) {
        case "initialize" -> {
          if (cmdArgs.length < 1) {
            throw new IllegalArgumentException(
                "initialize requires nodeId@cluster");
          }
          admin.initialize(cmdArgs[0],
              cmdArgs.length > 1 ? Optional.of(cmdArgs[1]) : Optional.empty(),
              cmdArgs.length > 2 ? Optional.of(cmdArgs[2]) : Optional.empty());
        }
        case "add_node_verifier" -> {
          if (cmdArgs.length != 2) {
            throw new IllegalArgumentException(
                "add_node_verifier requires nodeId@cluster and N,g,s,v");
          }
          admin.addNodeVerifier(cmdArgs[0], cmdArgs[1]);
        }
        case "set_node_address" -> {
          if (cmdArgs.length != 2) {
            throw new IllegalArgumentException(
                "set_node_address requires nodeId@cluster and host:port");
          }
          admin.setNodeAddress(cmdArgs[0], cmdArgs[1]);
        }
        case "list_verifiers" -> admin.listVerifiers();
        case "list_addresses" -> admin.listAddresses();
        default -> throw new IllegalArgumentException("Unknown command: " + cmd);
      }
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      System.exit(1);
    }
  }
}
