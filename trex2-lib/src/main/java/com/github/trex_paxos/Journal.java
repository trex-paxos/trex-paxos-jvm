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

/// The journal is the storage layer of the Paxos Algorithm. It is also used as the replicated log for the state machine.
/// This API is designed to be simple and easy to implement via a NavigableMap interface such as a BTreeMap or MVStore.
/// Yet you could implement it with a SQL database or a distributed key value store that has ordered keys.
///
/// VERY IMPORTANT: The journal must be crash durable. The TrexNode will the `sync()` method on the journal which must
/// can only return when the state is persisted.
///
/// VERY IMPORTANT: The state of the `Progress` must be flushed last if your store does not support transactions.
///
///
/// When an empty node is fist created the journal must have a `NoOperation.NOOP` accept journaled at log index 0.
/// It must also have the nodes progress saved as `new Progress(noteIdentifier)`. When a cold cluster is started up the
/// nodes will time out to and will attempt to prepare the slot 1 which is why it must container a NOOP.
///
/// If you want to clone a node to create a new one you must copy the journal and the application state. Then update
/// the journal state to have new node identifier. This is because the journal is node specific, and we must
/// not accidentally load the wrong history when moving nodes between servers. If you are using a sql database to hold
/// the state this might look like 'update progress set node_identifier = ?' where the node_identifier is the new node.
/// If you are using an embedded btree you could 'loadProcess', modify the progress with java, then 'saveProgress'.
public interface Journal {
  /**
   * Save the progress record to durable storage. This method must force the disk.
   *
   * @param progress The highest promised, fixed and accepted operationBytes.
   */
  void saveProgress(Progress progress);

  /**
   * Save the accept record to the log. This method must force the disk.
   *
   * @param accept An accept that is not yet chosen until the log index is fixed.
   */
  void journalAccept(Accept accept);

  /**
   * Load the progress record from durable storage.
   *
   * @param nodeIdentifier The node identifier to load the progress record for. To avoid accidentally loading the wrong
   *                       history when moving nodes between servers we require the node identifier. This is only a safety feature.
   */
  Progress loadProgress(byte nodeIdentifier);

  /**
   * Load any accept record from the log. There may be no accept record for the given log index.
   *
   * @param logIndex The log slot to load the accept record for.
   */
  Optional<Accept> loadAccept(long logIndex);

  /// Sync the journal to disk. This method must force the disk. This must be done before any messages are sent out.
  void sync();

  /// Get the highest log index that has been journaled. This is used to speed up recovery from crashes during the
  /// leader recovery process.
  long highestLogIndex();
}
