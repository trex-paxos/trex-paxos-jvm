// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.network;

import com.github.trex_paxos.*;
import com.github.trex_paxos.msg.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static com.github.trex_paxos.CommandPickler.readProgress;
import static com.github.trex_paxos.CommandPickler.writeProgress;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CommandPicklerMsgTests {

  @Test
  public void testPreparePickleUnpickle() {
    Prepare prepare = new Prepare((short) 1, 2L, new BallotNumber((short) 3, 4, (short) 5));
    byte[] pickled = PickleMsg.pickle(prepare);
    Prepare unpickled = (Prepare) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(prepare, unpickled);
  }

  @Test
  public void testAcceptNoopPickleUnpickle() {
    Accept accept = new Accept((short) 3, 4L, new BallotNumber((short) 5, 6, (short) 7), NoOperation.NOOP);
    byte[] pickled = PickleMsg.pickle(accept);
    Accept unpickled = (Accept) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptPickleUnpickleClientCommand() {
    Command command = new Command("data".getBytes(StandardCharsets.UTF_8));
    Accept accept = new Accept((short) 1, 2L, new BallotNumber((short) 3, 4, (short) 5), command);
    byte[] pickled = PickleMsg.pickle(accept);
    Accept unpickled = (Accept) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptPickleUnpickleNoop() {
    Accept accept = new Accept((short) 1, 2L, new BallotNumber((short) 3, 4, (short) 5), NoOperation.NOOP);
    byte[] pickled = PickleMsg.pickle(accept);
    Accept unpickled = (Accept) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptNackPickleUnpickle() {
    AcceptResponse acceptNack = new AcceptResponse(
        (short) 1, (short) 2, (short)3,
        new AcceptResponse.Vote((short) 1, (short) 2, new SlotTerm(6L, new BallotNumber((short) 7, 8, (short)9)), true),
        10L);
    byte[] pickled = PickleMsg.pickle(acceptNack);
    AcceptResponse unpickled = (AcceptResponse) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(acceptNack, unpickled);
  }

  @Test
  public void testPrepareResponsePickleUnpickleNoop() {
    final var accept = new Accept((short) 1, 2L, new BallotNumber((short) 3, 4, (short) 5), NoOperation.NOOP);
    PrepareResponse prepareAck = new PrepareResponse(
        (short) 1,
        (short) 2,
        (short) 3,
        new PrepareResponse.Vote(
            (short) 1,
            (short) 2,
            new SlotTerm(6L, new BallotNumber((short) 7, 8, (short) 9)),
            true),
            Optional.of(accept),
            1234213424L
        );
    byte[] pickled = PickleMsg.pickle(prepareAck);
    PrepareResponse unpickled = (PrepareResponse) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(prepareAck, unpickled);
  }

  @Test
  public void testPrepareResponsePickleUnpickleClientCommand() {
    final var cmd = new Command("data".getBytes(StandardCharsets.UTF_8));
    final var accept = new Accept((short) 1, 2L, new BallotNumber((short) 3, 4, (short) 5), cmd);
    PrepareResponse prepareAck = new PrepareResponse(
        (short) 6, (short) 7, (short)8,
        new PrepareResponse.Vote((short) 6, (short) 7, new SlotTerm(11L, new BallotNumber((short) 12, 13, (short) 14)), true),
        Optional.of(accept), 1234213424L);
    byte[] pickled = PickleMsg.pickle(prepareAck);
    PrepareResponse unpickled = (PrepareResponse) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(prepareAck.journaledAccept(), unpickled.journaledAccept());
    assertEquals(prepareAck, unpickled);
  }

  @Test
  public void testPickleProgress() throws Exception {
    Progress progress = new Progress((short) 1, new BallotNumber((short) 2, 3, (short) 4), 5L);
    byte[] pickled = writeProgress(progress);
    Progress unpickled = readProgress(pickled);
    assertEquals(progress, unpickled);
  }

  @Test
  public void testPickFixed() {
    Fixed fixed = new Fixed(
        (short) 1,
        2L, new BallotNumber((short) 3, 4, (short) 5));
    byte[] pickled = PickleMsg.pickle(fixed);
    Fixed unpickled = (Fixed) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(fixed, unpickled);
  }

  @Test
  public void testPickleCatchup() {
    Catchup catchup = new Catchup((short) 1, (short) 2, 3L, new BallotNumber((short) 4, 5, (short) 6));
    byte[] pickled = PickleMsg.pickle(catchup);
    Catchup unpickled = (Catchup) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(catchup.from(), unpickled.from());
    assertEquals(catchup.to(), unpickled.to());
  }

  @Test
  public void testPickleCatchupResponse() {
    final Accept[] accepts = {
        new Accept((short) 1, 2L, new BallotNumber((short) 3, 4, (short) 5), NoOperation.NOOP),
        new Accept((short) 6, 7L, new BallotNumber((short) 8, 9, (short) 10), new Command("data".getBytes(StandardCharsets.UTF_8), (byte)11)),
    };
    CatchupResponse catchupResponse = new CatchupResponse((short) 12, (short) 13, Arrays.asList(accepts));
    byte[] pickled = PickleMsg.pickle(catchupResponse);
    CatchupResponse unpickled = (CatchupResponse) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(catchupResponse.from(), unpickled.from());
    assertEquals(catchupResponse.to(), unpickled.to());
    assertArrayEquals(catchupResponse.accepts().toArray(), unpickled.accepts().toArray());
  }
}
