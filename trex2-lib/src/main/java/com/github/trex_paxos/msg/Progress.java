package com.github.trex_paxos.msg;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Progress is a record of the highest ballot number promised which must be forced to disk for Paxos to be correct.
 * It is also the highest accepted command and the highest committed command which is used to speed up node recovery.
 *
 * @param nodeIdentifier The current node identifier.
 * @param highestPromised
 * @param highestCommitted
 * @param highestAccepted
 */
public record Progress(byte nodeIdentifier, BallotNumber highestPromised, long highestCommitted,
                       long highestAccepted) implements JournalRecord {

  /**
   * When an application initializes an empty journal it has to have a NIL value.
   *
   * @param nodeIdentifier The current node identifier.
   */
  @SuppressWarnings("unused")
  public Progress(byte nodeIdentifier) {
    this(nodeIdentifier, BallotNumber.MIN, 0, 0);
  }

  public Progress withHighestCommitted(long committedLogIndex) {
    return new Progress(nodeIdentifier, highestPromised, committedLogIndex, highestAccepted);
    }

    // Java may get `with` so that we can retire this method.
    public Progress withHighestPromised(BallotNumber p) {
      return new Progress(nodeIdentifier, p, highestCommitted, highestAccepted);
    }

    public void writeTo(DataOutputStream dos) throws IOException {
      dos.writeByte(nodeIdentifier);
      highestPromised.writeTo(dos);
      dos.writeLong(highestCommitted);
      dos.writeLong(highestAccepted);
    }

    public static Progress readFrom(DataInputStream dis) throws IOException {
      return new Progress(dis.readByte(), BallotNumber.readFrom(dis), dis.readLong(), dis.readLong());
    }

    @Override
    public String toString() {
      return "P(p={" + highestPromised + "},c={" + highestCommitted + "},a={" + highestAccepted + "})";
    }
}
