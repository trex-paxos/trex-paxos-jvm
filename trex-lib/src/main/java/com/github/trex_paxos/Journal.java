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

/// The journal is the storage layer of the Paxos Algorithm. Journal writes must be crash-proof (disk flush or equivalent).
///
/// When an empty node is fist created the journal must have a `NoOperation.NOOP` accept journaled at log index 0.
/// It must also have the nodes progress saved as `new Progress(noteIdentifier)` which defaults to the minimum ballot number. .
///
/// If you are already using a relational database you can use it to store the state of the journal in two simple tables.
/// The `progress` table will have one row. The accept table will have a row for each `accept` message.
///
/// If you use a store that does not support transactions when the [TrexEngine] will call {@link #sync()} and you must
/// make the state crash durable in the following order:
///
/// 1. All `accept` messages in the log must be made crash durable first.
/// 2. The `progress` record is made crash durable last.
///
/// If you are using a store that supports transactions you can commit your application writes in the same transaction
/// as the journal writes. SUse the {@link TrexEngine} constructor with the `hostManagedTransactions=true` flag set to true.
/// This will prevent the TrexEngine from calling the [#sync()] method. You then must commit the database transaction after
/// *every* call to [TrexEngine#paxos(java.util.List)] and before any messages are transmitted to the network. When that
/// method return fixed results you can apply the results to your database and then commit the transaction. Once again
/// this must happen before ending any messages to the network.
///
/// It is important to note you should not delete `accept` messages the moment they are up-called into the application.
/// They should be kept around so that other nodes can request retransmission of missed messages. To
/// keep the database size under control you can run a cronjob that reads the {@link Progress#highestFixedIndex()}
/// from all databases and take the min id. You can then delete all `accept` messages from all nodes that are at a
/// lower log slot index.
///
/// VERY IMPORTANT: If you get errors where you don't know what the state of the underlying journal has become you should call
/// [TrexEngine#crash()] and restart the process.
///
/// If you want to clone a node to create a new one you must copy the journal and the application state. Then update
/// the journal state to have new node identifier in the progress record. This is because the journal is node specific,
/// and we must not accidentally mix the identity of the node when moving state between physical hosts.
///
/// Read the [#sync()] message for more information on the importance of crash durability.
public interface Journal {

  /// Save the promise and fixed log index into durable storage. If you get errors where you don't know what the state of the underlying
  /// you must call [TrexEngine#crash()] and restart the process.
  ///
  /// @param progress The highest promised number and the highest fixed slot index for a given node.
  void writeProgress(Progress progress);

  /// Load the progress record from durable storage. This is usually only done once at startup.
  ///
  /// @param nodeIdentifier The node identifier to load the progress record for. To avoid accidentally loading the wrong
  ///                                                                                                                                                           history when moving nodes between servers we require the node identifier. This is only a safety feature.
  Progress readProgress(short nodeIdentifier);

  /// Save a value wrapped in an `accept` into the log.
  /// Logically this method is storing `accept(S,N,V)` so it needs to store the values `{N,V}` at log slot `S`
  /// The `N` is the term number of the leader that generated the message.
  /// Typically, values are written in sequential `S` order.
  ///
  /// @param accept An accept message that is a log index, command id and term number.
  void writeAccept(Accept accept);

  /// Load any accept record from the log. There may be no accept record for the given log index.
  /// Typically, values are read once in sequential `S`. During crash recovery or syncing nodes they are reread.
  ///
  /// You should not delete any `accept` messages until you know all nodes have a higher [Progress#highestFixedIndex()]
  /// than the log index of the accept message.
  ///
  /// When a slot is learned to be fixed by a `fixed(S,N')` the id is read from the log and  if `N' != N` then
  /// Retransmission of the correct `accept` will be requested from the leader.
  ///
  /// @param logIndex The log slot to load the accept record for.
  /// @return The accept record if it exists at the specified log index.
  Optional<Accept> readAccept(long logIndex);

  /// The name of this is inspired by the [libc sync call](https://man7.org/linux/man-pages/man2/sync.2.html).
  /// In particular the difference between Linux and what POSIX.1-2001 does:
  ///
  /// > According to the standard specification (e.g., POSIX.1-2001),
  ///        `sync()` schedules the writes, but may return before the actual
  ///        writing is done.  However, Linux waits for I/O completions.
  ///
  /// It is not the case that POSIX.1-2001 does not have a hard sync it just that it calls it `fsync`. It is that sort
  /// of ambiguity that leads to data loss.
  ///
  /// You must choose whether you want a full `fsync` or not. You can choose to have a five node cluster with nodes
  /// spread across three resilient zones. You can tune the background flush interval of your database to hundreds of milliseconds.
  /// You may convince yourself that data will make it to disk on a majority of nodes without calling fsync. Yet do not
  /// hide the risks from your users.
  ///
  /// See also what [PostgreSQL](https://www.postgresql.org/docs/8.1/runtime-config-wal.html?t) writes about `fsync``
  /// as a good background read no matter what database or data store you are using.
  void sync();

  /// Get the highest log index that has been journaled. This is used to during startup. If you are using a relational
  /// database then all only needs to do of a pk which is very quick, If you are using an embedded btree then finding the last
  /// key is usually very quick. If you are using a log structured store it may require a scan of all keys or worst all
  /// records to find the highest log index. As this is done at startup it may, or may not, be a problem.
  long highestLogIndex();
}
