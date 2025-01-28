package com.github.trex_paxos.paxe.demo;

import com.github.trex_paxos.*;
import com.github.trex_paxos.network.ClusterMembership;
import com.github.trex_paxos.network.NetworkAddress;
import com.github.trex_paxos.network.NodeId;
import com.github.trex_paxos.network.SystemChannel;
import com.github.trex_paxos.paxe.*;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;

public class ClusteredStackImpl implements AutoCloseable {

  private final MVStore store;
  private final MVMap<String, String> secretMap;
  private final MVMap<String, String> passwordFileMap;
  private final MVMap<String, String> networkMap;
  private final MVMap<Long, String> stackMap;
  private final Identity identity;
  private final PaxeNetwork network;
  private final TrexApp<StackCommand, StackResponse> app;
  private final Stack<String> stack = new Stack<>();

  sealed interface StackCommand {
    record Push(String value) implements StackCommand {
    }

    record Pop() implements StackCommand {
    }

    record Peek() implements StackCommand {
    }
  }

  public record StackResponse(Optional<String> value) {
  }

  private record DynamicMajorityQuorum(Supplier<ClusterMembership> membershipSupplier) implements QuorumStrategy {

    @Override
    public QuorumOutcome assessPromises(long logIndex, Set<com.github.trex_paxos.msg.PrepareResponse.Vote> promises) {
      int quorumSize = calculateQuorum();
      long votes = promises.stream().filter(com.github.trex_paxos.msg.PrepareResponse.Vote::vote).count();
      return getOutcome(votes, quorumSize);
    }

    @Override
    public QuorumOutcome assessAccepts(long logIndex, Set<com.github.trex_paxos.msg.AcceptResponse.Vote> accepts) {
      int quorumSize = calculateQuorum();
      long votes = accepts.stream().filter(com.github.trex_paxos.msg.AcceptResponse.Vote::vote).count();
      return getOutcome(votes, quorumSize);
    }

    private QuorumOutcome getOutcome(long votes, int quorumSize) {
      if (votes >= quorumSize) return QuorumOutcome.WIN;
      if (votes + quorumSize < clusterSize()) return QuorumOutcome.LOSE;
      return QuorumOutcome.WAIT;
    }

    @Override
    public int clusterSize() {
      return membershipSupplier.get().nodeAddresses().size();
    }

    private int calculateQuorum() {
      return (clusterSize() / 2) + 1;
    }
  }

  public ClusteredStackImpl(String filename, String id, int port) {
    store = new MVStore.Builder().fileName(filename).open();
    secretMap = store.openMap(ClusterStackAdmin.SECRET_MAP);
    passwordFileMap = store.openMap(ClusterStackAdmin.PASSWORD_FILE_MAP);
    networkMap = store.openMap(ClusterStackAdmin.NETWORK_MAP);
    stackMap = store.openMap("stack_data");
    identity = Identity.from(id);

    validateIdentity();
    validateMinimumNodes();

    // Reconstruct stack state
    // FIXME this is junk. we should be recording if it was a push or a pop or whatever. we have searalised commands in the journal that we can apply and log here.
    // come to think of it we can simply just read the log from the journal and replay it from the beginning no need to have some other way of storing the state of the stack
    if (!stackMap.isEmpty()) {
      var maxKey = stackMap.lastKey();
      for (long i = 0; i <= maxKey; i++) {
        Optional.ofNullable(stackMap.get(i)).ifPresent(stack::push);
      }
    }

    network = createNetwork(port);
    app = createApp(network);
    registerSignalHandler();
  }

  private void validateIdentity() {
    if (!secretMap.containsKey(identity.full())) {
      throw new IllegalStateException("Store not initialized for identity: " + identity.full());
    }
  }

  private void validateMinimumNodes() {
    if (passwordFileMap.isEmpty()) {
      throw new IllegalStateException("At least one other node must be configured before starting");
    }
  }

  private Map<NodeId, NetworkAddress> loadNetworkAddresses() {
    Map<NodeId, NetworkAddress> addresses = new HashMap<>();
    networkMap.forEach((id, addr) -> {
      var nodeId = new NodeId(Identity.from(id).nodeId());
      String[] parts = addr.split(":", 2);
      addresses.put(nodeId, new NetworkAddress.HostName(parts[0], Integer.parseInt(parts[1])));
    });
    return addresses;
  }

  private PaxeNetwork createNetwork(int port) {
    String[] secretParts = secretMap.get(identity.full()).split(",");
    NodeClientSecret secret = new NodeClientSecret(new ClusterId(identity.cluster()), new NodeId(identity.nodeId()), secretParts[1], // password
        SRPUtils.fromHex(secretParts[0]) // salt
    );

    Map<NodeId, NodeVerifier> verifiers = new HashMap<>();
    passwordFileMap.forEach((id, params) -> {
      var nodeId = Identity.from(id).nodeId();
      String[] parts = params.split(",");
      verifiers.put(new NodeId(nodeId), new NodeVerifier(id, parts[3])); // verifier is 4th part
    });

    SessionKeyManager keyManager = new SessionKeyManager(new NodeId(identity.nodeId()),
        new SRPUtils.Constants(ClusterStackAdmin.DEFAULT_N, ClusterStackAdmin.DEFAULT_G), secret, () -> verifiers);

    try {
      return new PaxeNetwork.Builder(keyManager, port, new NodeId(identity.nodeId()), currentMembership).build();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create network", e);
    }
  }

  protected Supplier<ClusterMembership> currentMembership = () -> new ClusterMembership(loadNetworkAddresses());

  private TrexApp<StackCommand, StackResponse> createApp(PaxeNetwork network) {
    var scheduler = new TimeoutScheduler(identity.nodeId());
    return new TrexApp<>(currentMembership, new TrexEngine(new TrexNode(Level.INFO, identity.nodeId(), new DynamicMajorityQuorum(currentMembership), new MVStoreJournal(store))) {
      @Override
      protected void setRandomTimeout() {
        scheduler.setTimeout(() -> timeout().ifPresent(prepare -> network.broadcast(currentMembership, SystemChannel.CONSENSUS.value(), prepare)));
      }

      @Override
      protected void clearTimeout() {
        scheduler.clearTimeout();
      }

      @Override
      protected void setNextHeartbeat() {
        scheduler.setHeartbeat(this::createHeartbeatMessagesAndReschedule);
      }

      @Override
      public void close() {
        super.close();
        scheduler.close();
      }
    }, network, PermitsRecordsPickler.createPickler(StackCommand.class), this::processCommand);
  }

  private StackResponse processCommand(StackCommand cmd) {
    synchronized (stack) {
      try {
        return switch (cmd) {
          case StackCommand.Push p -> {
            stack.push(p.value());
            stackMap.put((long) stack.size() - 1, p.value());
            store.commit();
            yield new StackResponse(Optional.empty());
          }
          case StackCommand.Pop _ -> {
            if (stack.isEmpty()) {
              yield new StackResponse(Optional.of("Stack is empty"));
            }
            String value = stack.pop();
            stackMap.remove((long) stack.size());
            store.commit();
            yield new StackResponse(Optional.of(value));
          }
          case StackCommand.Peek _ -> {
            if (stack.isEmpty()) {
              yield new StackResponse(Optional.of("Stack is empty"));
            }
            yield new StackResponse(Optional.of(stack.peek()));
          }
        };
      } catch (Exception e) {
        LOGGER.warning(() -> "Command processing failed: " + e.getMessage());
        return new StackResponse(Optional.of("Error: " + e.getMessage()));
      }
    }
  }

  public void start() {
    app.start();
  }

  @SuppressWarnings("unused")
  public StackResponse push(String value) {
    CompletableFuture<StackResponse> future = new CompletableFuture<>();
    app.submitValue(new StackCommand.Push(value), future);
    try {
      return future.get();
    } catch (Exception e) {
      throw new RuntimeException("Push failed", e);
    }
  }

  @SuppressWarnings("unused")
  public StackResponse pop() {
    CompletableFuture<StackResponse> future = new CompletableFuture<>();
    app.submitValue(new StackCommand.Pop(), future);
    try {
      return future.get();
    } catch (Exception e) {
      throw new RuntimeException("Pop failed", e);
    }
  }

  @SuppressWarnings("unused")
  public StackResponse peek() {
    CompletableFuture<StackResponse> future = new CompletableFuture<>();
    app.submitValue(new StackCommand.Peek(), future);
    try {
      return future.get();
    } catch (Exception e) {
      throw new RuntimeException("Peek failed", e);
    }
  }

  private void registerSignalHandler() {
    Runtime.getRuntime().addShutdownHook(new Thread(this::close));

    // Handle HUP signal for membership reload

    //sun.misc.Signal.handle(new sun.misc.Signal("HUP"), sig -> LOGGER.info("Received HUP signal - membership will be reloaded on next access"));
  }

  @Override
  public void close() {
    try {
      if (app != null) app.stop();
      if (network != null) network.close();
    } finally {
      if (store != null) store.close();
    }
  }

  public static void main(String[] args) {
    if (args.length != 3) {
      System.err.println("Usage: " + ClusteredStackImpl.class.getName() + " <store.db> <nodeId@cluster> <port>");
      System.exit(1);
    }

    try (var stack = new ClusteredStackImpl(args[0], args[1], Integer.parseInt(args[2]))) {
      stack.start();

      // Keep running until terminated
      Thread.currentThread().join();

    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      System.exit(1);
    }
  }
}
