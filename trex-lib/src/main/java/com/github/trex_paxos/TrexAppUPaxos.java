package com.github.trex_paxos;

import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.network.NetworkLayer;
import com.github.trex_paxos.network.NodeEndpoint;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static com.github.trex_paxos.network.SystemChannel.PROXY;

/// UPaxos performs uses the techniques described in the UPaxos paper [Unbounded Pipelining in Dynamically Reconfigurable Paxos Clusters](http://tessanddave.com/paxos-reconf-latest.pdf).
/// This is done by intercepting messages rather than changing the core algorithm logic.
/// @param <COMMAND> The type of commands submitted to the consensus algorithm
/// @param <RESULT> The type of results produced after commands are chosen
public class TrexAppUPaxos<COMMAND, RESULT> {

  private final NetworkLayer networkLayer;

  public void submitValue(COMMAND value, CompletableFuture<RESULT> future) {
    trexApp.submitValue(value, future);
  }

  public void submitValue(COMMAND value, CompletableFuture<RESULT> future, byte type) {
    trexApp.submitValue(value, future, type);
  }

  // here we must enter overlap mode having sent out the next message to the cluster
  public void reconfigure(MemberReconfiguration reconfiguration, CompletableFuture<Boolean> future) {
    //trexApp.reconfigure(reconfiguration, future);
  }

  final protected TrexApp<COMMAND, RESULT> trexApp;

  /// Constructs a new TrexAppUPaxos instance.
  ///
  /// @param nodeEndpointSupplier Supplies the node endpoints for the cluster
  /// @param engine The consensus engine that implements the Paxos algorithm
  /// @param networkLayer The network communication layer
  /// @param valuePickler Serializer/deserializer for commands
  public TrexAppUPaxos(
      Supplier<NodeEndpoint> nodeEndpointSupplier,
      TrexEngine<RESULT> engine,
      NetworkLayer networkLayer,
      Pickler<COMMAND> valuePickler) {
    // we need the network layer to send outbound message to overlapping regions.
    this.networkLayer = networkLayer;
    // we need the trex engine to delegate to else give specific messages such as prepare.
    trexApp = new TrexApp<>(nodeEndpointSupplier, engine, networkLayer, valuePickler);
    // we remove the trex app from the consensus channel and replace it with our own overlap method that delegates
    this.networkLayer.subscribe(CONSENSUS.value(), this::overlapOrDelegate, "upaxos-" + engine.nodeIdentifier());
    this.networkLayer.subscribe(PROXY.value(), trexApp::handleProxyMessage, "proxy-" + engine.nodeIdentifier());
    this.networkLayer.start();
    TrexLogger.LOGGER.info(() -> "Node " + engine.nodeIdentifier() + " started successfully");
  }

  /// We must
  ///
  protected void overlapOrDelegate(TrexMessage msg) {



    // TODO: Implement the logic to overlap or delegate the command
    trexApp.handleConsensusMessage(msg);
  }

  public void start() {
    trexApp.init();
  }

  @SuppressWarnings("unused")
  public void stop() {
    trexApp.stop();
  }
}
