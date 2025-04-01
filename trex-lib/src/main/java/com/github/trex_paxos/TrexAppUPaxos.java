package com.github.trex_paxos;

import com.github.trex_paxos.network.NetworkLayer;
import com.github.trex_paxos.network.NodeEndpoint;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/// UPaxos performs uses the techniques described in the UPaxos paper [Unbounded Pipelining in Dynamically Reconfigurable Paxos Clusters](http://tessanddave.com/paxos-reconf-latest.pdf).
/// This is done by intercepting messages rather than changing the core algorithm logic.
/// @param <COMMAND> The type of commands submitted to the consensus algorithm
/// @param <RESULT> The type of results produced after commands are chosen
public class TrexAppUPaxos<COMMAND, RESULT> {

  public void submitValue(COMMAND value, CompletableFuture<RESULT> future) {
    trexApp.submitValue(value, future);
  }

  public void submitValue(COMMAND value, CompletableFuture<RESULT> future, byte type) {
    trexApp.submitValue(value, future, type);
  }

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
    trexApp = new TrexApp<>(nodeEndpointSupplier, engine, networkLayer, valuePickler);
  }

  public void start() {
    trexApp.start();
  }
  public void stop() {
    trexApp.stop();
  }
}
