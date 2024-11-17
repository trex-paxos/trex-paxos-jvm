package com.github.trex_paxos.msg;

import com.github.trex_paxos.Pickle;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Progress is a record of the highest ballot number promised or seen on an accepted message which must be crash durable
 * (e.g. forced to disk) for Paxos to be correct. We also store the highest committed index and the highest accepted index.
 *
 * @param nodeIdentifier The current node identifier.
 * @param highestPromised The highest ballot number promised or seen on an accepted message.
 * @param highestCommittedIndex The highest log index that has been learnt to have been fixed and so committed.
 * @param highestAcceptedIndex The highest log index that has been accepted.
 */
public record Progress(
    byte nodeIdentifier,
    BallotNumber highestPromised,
    long highestCommittedIndex,
    long highestAcceptedIndex
) {

  /**
   * When an application initializes an empty journal it has to have a NIL value.
   *
   * @param nodeIdentifier The current node identifier.
   */
  public Progress(byte nodeIdentifier) {
    this(nodeIdentifier, BallotNumber.MIN, 0, 0);
  }

  public Progress withHighestCommitted(long committedLogIndex) {
    return new Progress(nodeIdentifier, highestPromised, committedLogIndex, highestAcceptedIndex);
    }

    // Java may get `with` so that we can retire this method.
    public Progress withHighestPromised(BallotNumber p) {
      return new Progress(nodeIdentifier, p, highestCommittedIndex, highestAcceptedIndex);
    }

  public static void writeTo(Progress m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.nodeIdentifier());
    Pickle.write(m.highestPromised(), dos);
    dos.writeLong(m.highestCommittedIndex());
    dos.writeLong(m.highestAcceptedIndex());
    }

    public static Progress readFrom(DataInputStream dis) throws IOException {
      return new Progress(dis.readByte(), Pickle.readBallotNumber(dis), dis.readLong(), dis.readLong());
    }

    @Override
    public String toString() {
      return "P(p={" + highestPromised + "},c={" + highestCommittedIndex + "},a={" + highestAcceptedIndex + "})";
    }

  public Progress withHighestAccepted(long highestAcceptedIndex) {
    return new Progress(nodeIdentifier, highestPromised, highestCommittedIndex, highestAcceptedIndex);
  }
}
