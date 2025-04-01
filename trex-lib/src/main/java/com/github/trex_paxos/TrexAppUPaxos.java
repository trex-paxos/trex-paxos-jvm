package com.github.trex_paxos;

import com.github.trex_paxos.network.NetworkLayer;
import com.github.trex_paxos.network.NodeEndpoint;

import java.util.function.Supplier;

/// Implementation of TrexApp that uses uPaxos, a variant of Paxos that performs the
/// techniques described in the UPaxos paper [Unbounded Pipelining in Dynamically Reconfigurable Paxos Clusters](http://tessanddave.com/paxos-reconf-latest.pdf).
///
/// @param <COMMAND> The type of commands submitted to the consensus algorithm
/// @param <RESULT> The type of results produced after commands are chosen
public class TrexAppUPaxos<COMMAND, RESULT> extends TrexApp<COMMAND, RESULT> {
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
    super(nodeEndpointSupplier, engine, networkLayer, valuePickler);
  }


}
