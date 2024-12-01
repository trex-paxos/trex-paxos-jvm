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
/// If you are already using a relational database you can use it to store the state of the journal. Then you can use
/// a database transaction to save the modified state of your application in the same database transaction that you
/// persist the progress record.
///
/// It is important to note you should not delete accept messages the moment they are up-called into the application.
/// They should be kept around so that other nodes can request retransmission of them if they have missed them. To
/// keep the database size under control you can run a cronjob that reads the min {@link Progress#highestFixedIndex()}
/// from all database. It is then safe to delete all `Accept` messages stored at a lower slot index.
///
/// Many databases suggest that if you have very large values you should store them in an external store and only store
/// the reference in the database.
///
/// Yet you do not have to use a relational database. You can use kv stores or document stores. You can even use different
/// stores for the different state types as long as when {@link #sync()} is called the state is made crash durable in
/// the following order:
///
/// 1. All `accept` messages are made crash durable first.
/// 2. The `progress` record is made crash durable last.
///
/// VERY IMPORTANT: The journal must be crash durable. The TrexNode will the {@link #sync()} method on the journal which must
/// only return when the state is persisted.
///
/// When an empty node is fist created the journal must have a `NoOperation.NOOP` accept journaled at log index 0.
/// It must also have the nodes progress saved as `new Progress(noteIdentifier)`. When a cold cluster is started up the
/// nodes will time out to and will attempt to prepare the slot 1 which is why it must contain a genesis NOOP.
///
/// If you want to clone a node to create a new one you must copy the journal and the application state. Then update
/// the journal state to have new node identifier in the progress record. This is because the journal is node specific,
/// and we must not accidentally load the wrong history when moving nodes between servers.
///
/// If you are using a sql database to hold the state this might look like 'update progress set node_identifier = ?'
/// where the node_identifier is updated to the new unique node identifier.
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

  /// Journal writes must be crash-proof (disk flush or equivalent). The `sync()` method must first flush any `accept`
  /// messages into their slots and only then flush the `progress` record. .
  void sync();

  /// Get the highest log index that has been journaled. This is used to during startup. If you are using a relational
  /// database then all only needs to do of a pk which is very quick, If you are using an embedded btree then finding the last
  /// key is usually very quick. If you are using a log structured store it may require a scan of all keys or worst all
  /// records to find the highest log index. As this is done at startup it may, or may not, be a problem.
  long highestLogIndex();
}
