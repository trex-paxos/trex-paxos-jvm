package com.github.trex_paxos.paxe.demo;

import com.github.trex_paxos.*;
import com.github.trex_paxos.msg.PrepareResponse;
import com.github.trex_paxos.network.ClusterMembership;
import com.github.trex_paxos.network.NodeId;
import com.github.trex_paxos.paxe.*;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.util.HashMap;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

public class StackApp implements StackService, AutoCloseable {
  private final Stack<String> stack;
  private final TrexApp<StackWireProtocol.Command, StackWireProtocol.Result> app;

  private StackApp(Stack<String> stack, TrexApp<StackWireProtocol.Command, StackWireProtocol.Result> app) {
    this.stack = stack;
    this.app = app;
  }

  public static StackApp create(
      Identity identity,
      int port,
      MVStore store,
      Supplier<ClusterMembership> membership) {

    var stack = new Stack<String>();
    var network = createPaxeNetwork(identity, port, store, membership);
    var engine = createEngine(identity.nodeId(), store, membership, network);

    var app = new TrexApp<>(
        membership,
        engine,
        network,
        PermitsRecordsPickler.createPickler(StackWireProtocol.Command.class)
    );

    return new StackApp(stack, app);
  }

  /// process command is called by the paxos upcall which is done when holding a mutex so no need to synchronize
  static StackWireProtocol.Result processCommand(Stack<String> stack, StackWireProtocol.Command cmd) {
    return switch (cmd) {
      case StackWireProtocol.Push p -> new StackWireProtocol.Result(stack.push(p.value()));
      case StackWireProtocol.Pop _ -> new StackWireProtocol.Result(stack.pop());
      case StackWireProtocol.Peek _ -> new StackWireProtocol.Result(stack.peek());
    };
  }

  private static PaxeNetwork createPaxeNetwork(
      Identity identity,
      int port,
      MVStore store,
      Supplier<ClusterMembership> membership) {

    final MVMap<String, String> secretMap = store.openMap(ClusterStackAdmin.SECRET_MAP);
    final MVMap<String, String> passwordFileMap = store.openMap(ClusterStackAdmin.PASSWORD_FILE_MAP);

    String[] secretParts = secretMap.get(identity.full()).split(",");
    var secret = new NodeClientSecret(
        new ClusterId(identity.cluster()),
        new NodeId(identity.nodeId()),
        secretParts[1],
        SRPUtils.fromHex(secretParts[0])
    );

    var verifiers = new HashMap<NodeId, NodeVerifier>();
    passwordFileMap.forEach((id, params) -> {
      var nodeId = Identity.from(id).nodeId();
      String[] parts = params.split(",");
      verifiers.put(new NodeId(nodeId), new NodeVerifier(id, parts[3]));
    });

    var keyManager = new SessionKeyManager(
        new NodeId(identity.nodeId()),
        new SRPUtils.Constants(ClusterStackAdmin.DEFAULT_N, ClusterStackAdmin.DEFAULT_G),
        secret,
        () -> verifiers
    );

    try {
      return new PaxeNetwork.Builder(keyManager, port, new NodeId(identity.nodeId()), membership)
          .build();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create network", e);
    }
  }

  private static TrexEngine createEngine(
      short nodeId,
      MVStore store,
      Supplier<ClusterMembership> membership,
      PaxeNetwork network) {

    var scheduler = new TimeoutScheduler(nodeId);
    var node = new TrexNode(
        Level.INFO,
        nodeId,
        new DynamicMajorityQuorum(membership),
        new MVStoreJournal(store)
    );

    return new TrexEngine(node,
        (index, command) -> {
          throw new UnsupportedOperationException("Command handler not implemented");
        }
    );
  }

  @Override
  public String push(String value) {
    var future = new CompletableFuture<StackWireProtocol.Result>();
    app.submitValue(new StackWireProtocol.Push(value), future);
    try {
      return future.get().value();
    } catch (Exception e) {
      throw new RuntimeException("Push failed", e);
    }
  }

  @Override
  public String pop() {
    var future = new CompletableFuture<StackWireProtocol.Result>();
    app.submitValue(new StackWireProtocol.Pop(), future);
    try {
      return future.get().value();
    } catch (Exception e) {
      throw new RuntimeException("Pop failed", e);
    }
  }

  @Override
  public String peek() {
    var future = new CompletableFuture<StackWireProtocol.Result>();
    app.submitValue(new StackWireProtocol.Peek(), future);
    try {
      return future.get().value();
    } catch (Exception e) {
      throw new RuntimeException("Peek failed", e);
    }
  }

  public void start() {
    app.start();
  }

  @Override
  public void close() {
    app.stop();
  }

  private record DynamicMajorityQuorum(Supplier<ClusterMembership> membershipSupplier)
      implements QuorumStrategy {

    @Override
    public QuorumOutcome assessPromises(long logIndex, Set<PrepareResponse.Vote> promises) {
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
}
