package com.github.trex_paxos.msg;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/// A leader sends out a Commit when it learns of a new fixed log index. It will also heartbeat this message to keep
/// the followers from timing out. If a node was isolated and rejoins it will learn that it has missed out on some
/// log indexes and will request a Catchup.
///
/// @param from                  The node identifier of the leader.
/// @param highestCommittedIndex The highest log index that the leader has learnt to have been fixed and so committed.
/// @param highestAcceptedIndex  The highest log index that the leader has attempted to fix.
public record Commit(
    // FIXME: we must add the Ballot Number for the follower to know it is commiting the same log entry as the leader.
    byte from,
    long highestCommittedIndex,
    long highestAcceptedIndex
) implements TrexMessage, BroadcastMessage {

  public void writeTo(DataOutputStream dos) throws IOException {
    dos.writeByte(from);
    dos.writeLong(highestAcceptedIndex);
    dos.writeLong(highestCommittedIndex);
  }

  public static Commit readFrom(DataInputStream dis)
    throws java.io.IOException {
    final var from = dis.readByte();
    final var highestAcceptedIndex = dis.readLong();
    final var highestCommittedIndex = dis.readLong();
    return new Commit(from, highestCommittedIndex, highestAcceptedIndex);
  }
}
