package com.github.trex_paxos.msg;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.stream.IntStream;

public record Catchup(byte from, byte to, long highestCommittedIndex,
                      long[] slotGaps) implements DirectMessage, TrexMessage {
  public static Catchup readFrom(DataInputStream dis) throws IOException {
    final var from = dis.readByte();
    final var to = dis.readByte();
    final var highestCommittedIndex = dis.readLong();
    final var length = dis.readShort();
    final long[] slotGaps = IntStream.range(0, length)
        .mapToLong(_ -> Pickle.uncheckedReadLong(dis))
        .toArray();
    return new Catchup(from, to, highestCommittedIndex, slotGaps);
  }

  public void writeTo(DataOutputStream dos) throws IOException {
    dos.writeByte(from);
    dos.writeByte(to);
    dos.writeLong(highestCommittedIndex);
    final var length = Math.min(slotGaps.length, Short.MAX_VALUE);
    dos.writeShort(length);
    IntStream.range(0, length).forEach(i -> Pickle.uncheckedWriteLong(dos, slotGaps[i]));
  }
}
