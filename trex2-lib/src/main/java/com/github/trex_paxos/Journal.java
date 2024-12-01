/*
 * Copyright 2024 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos;

import com.github.trex_paxos.msg.Accept;

import java.util.Optional;

/// The journal is the storage layer of the Paxos Algorithm.
///
/// You will need to turn messages into bytes and back again. There is a {@link Pickle} class that can help with this.
///
/// If you are already using a relational database you can use it to store the state of the journal. Then you can use
/// a database transaction to commit the work of the fixed commands within the same database transaction that you
/// persist the `progress` record. In which case use the {@link TrexEngine#TrexEngine(TrexNode, boolean)} constructor
/// with the `hostManagedTransactions` flag set to true. This will prevent the TrexEngine from calling the `sync()` method.
/// The host must then commit the underlying journal database transaction after it has applied all the fixed commands
/// to the application tables. Only then may it sends out any messages.
///
/// It is important to note you should not delete `accept` messages the moment they are up-called into the application.
/// They should be kept around so that other nodes can request retransmission of missed messages. To
/// keep the database size under control you can run a cronjob that reads the {@link Progress#highestFixedIndex()}
/// from all databases. It is then safe to delete all `Accept` messages stored at a lower than the min fixed index seen
/// within the cluster.
///
/// Many databases suggest that if you have very large values you should store them in an external store and only store
/// the reference in the database.
///
/// You do not have to use a relational database. You can use a kv stores or a document stores. MVStore is the
/// storage subsystem of H2. It supports transactions and would make a good choice for an embedded journal.
/// You would have the `sync()` method call `commit()` on the underlying MVStore that is behind two maps. One map would
/// only contain the progress value. The other navigable map would hold all the `accept` messages keyed by log slot.
///
/// If you use a store that does not support transactions when {@link #sync()} is called the state must be made crash
/// durable in the following order:
///
/// 1. All `accept` messages are made crash durable first.
/// 2. The `progress` record is made crash durable last.
///
/// VERY IMPORTANT: The journal must be crash durable. By default `{@link TrexEngine} will the {@link #sync()} method on
/// the journal which must only return when all state is persisted.
///
/// When an empty node is fist created the journal must have a `NoOperation.NOOP` accept journaled at log index 0.
/// It must also have the nodes progress saved as `new Progress(noteIdentifier)`. When a cold cluster is started up the
/// nodes will time out to and will attempt to prepare the slot 1 which is why it must contain a genesis NOOP.
///
/// If you want to clone a node to create a new one you must copy the journal and the application state. Then update
/// the journal state to have new node identifier in the progress record. This is because the journal is node specific,
/// and we must not accidentally mix the identity of the node when moving state between physical hosts.
///
/// If you are using a sql database restore to create a new node the SQL might look like 'update progress set node_identifier = ?'
/// where the node_identifier is the new unique node identifier.
///
/// If you are using an embedded btree you could {@link #readProgress(byte)}, modify the progress with java,
/// then {@link #writeProgress(Progress)}
public interface Journal {

  /// Save the progress record to durable storage. The {@link #sync()} method must be called before returning any messages
  /// from any logic that called this method.
  ///
  /// @param progress The highest promised number and the highest fixed slot index for a given node.
  void writeProgress(Progress progress);

  /// Save the accept record to the log. The {@link #sync()} method must be called before returning any messages
  /// from any logic that called this method.
  ///
  /// Logically this is method is storing `accept(S,N,V)` so it needs to store under log key `S` the values `{N,V}`.
  /// Typically, values are written in sequential `S` order.
  ///
  /// @param accept An accept message that has a log index and a value to be stored in the log.
  void writeAccept(Accept accept);

  /// Load the progress record from durable storage.
  ///
  /// @param nodeIdentifier The node identifier to load the progress record for. To avoid accidentally loading the wrong
  ///                                                                                                                                                           history when moving nodes between servers we require the node identifier. This is only a safety feature.
  Progress readProgress(byte nodeIdentifier);

  /// Load any accept record from the log. There may be no accept record for the given log index.
  /// Typically, values are read in sequential `S` order for crash recovery or syncing nodes.
  ///
  /// @param logIndex The log slot to load the accept record for.
  Optional<Accept> readAccept(long logIndex);

  /// Journal writes must be crash-proof (disk flush or equivalent). If you are using transactional storage you would
  /// ensure that write of the `progress` and all `accepts` happens automatically. If your storage does not support
  /// transactions you just first flush any `accept` messages into their slots and only then flush the `progress` record.
  ///
  /// If the {@link TrexEngine#TrexEngine(TrexNode, boolean)} is constructed with `hostManagedTransactions` set to true
  /// this method is never called. It is then the responsibility of the host application to ensure that the underlying database
  /// transactions are committed before any messages are sent out.
  void sync();

  /// Get the highest log index that has been journaled. This is used to during startup. If you are using a relational
  /// database then all only needs to do of a pk which is very quick, If you are using an embedded btree then finding the last
  /// key is usually very quick. If you are using a log structured store it may require a scan of all keys or worst all
  /// records to find the highest log index. As this is done at startup it may, or may not, be a problem.
  long highestLogIndex();
}
