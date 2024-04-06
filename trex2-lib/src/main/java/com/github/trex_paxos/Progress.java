package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Progress is a record of the highest ballot number promised which must be forced to disk for Paxos to be correct.
 * It is also the highest accepted command and the highest committed command which is used to speed up node recovery.
 *
 * @param highestPromised
 * @param highestCommitted
 * @param highestAccepted
 */
public record Progress(BallotNumber highestPromised, Identifier highestCommitted,
                       long highestAccepted) implements JournalRecord {

    public Progress withHighestCommitted(Identifier id) {
      return new Progress(highestPromised, id, highestAccepted);
    }

    // Java may get `with` so that we can retire this method.
    public Progress withHighestPromised(BallotNumber p) {
      return new Progress(p, highestCommitted, highestAccepted);
    }

    public void writeTo(DataOutputStream dos) throws IOException {
        highestPromised.writeTo(dos);
        highestCommitted.writeTo(dos);
      dos.writeLong(highestAccepted);
    }

    public static Progress readFrom(DataInputStream dis) throws IOException {
      return new Progress(BallotNumber.readFrom(dis), Identifier.readFrom(dis), dis.readLong());
    }

    @Override
    public String toString() {
      return STR."P(p=\{highestPromised},c=\{highestCommitted},a=\{highestAccepted})";
    }
}
