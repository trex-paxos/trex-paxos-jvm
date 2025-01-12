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
package com.github.trex_paxos.network;

import com.github.trex_paxos.msg.*;
import com.github.trex_paxos.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static com.github.trex_paxos.Pickle.readProgress;
import static com.github.trex_paxos.Pickle.writeProgress;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PickleMsgTests {

  @Test
  public void testPreparePickleUnpickle() throws IOException {
    Prepare prepare = new Prepare((short) 1, 2L, new BallotNumber(3, (short) 4));
    byte[] pickled = PickleMsg.pickle(prepare);
    Prepare unpickled = (Prepare) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(prepare, unpickled);
  }

  @Test
  public void testAcceptNoopPickleUnpickle() throws IOException {
    Accept accept = new Accept((short) 3, 4L, new BallotNumber(2, (short) 3), NoOperation.NOOP);
    byte[] pickled = PickleMsg.pickle(accept);
    Accept unpickled = (Accept) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptPickleUnpickleClientCommand() throws IOException {
    Command command = new Command("data".getBytes(StandardCharsets.UTF_8));
    Accept accept = new Accept((short) 3, 4L, new BallotNumber(2, (short) 3), command);
    byte[] pickled = PickleMsg.pickle(accept);
    Accept unpickled = (Accept) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptPickleUnpickleNoop() throws IOException {
    Accept accept = new Accept((short) 3, 4L, new BallotNumber(2, (short) 3), NoOperation.NOOP);
    byte[] pickled = PickleMsg.pickle(accept);
    Accept unpickled = (Accept) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptNackPickleUnpickle() throws IOException {
    AcceptResponse acceptNack = new AcceptResponse(
        (short) 1, (short) 2,
        new AcceptResponse.Vote((short) 1, (short) 2, 4L, true),
        11L);
    byte[] pickled = PickleMsg.pickle(acceptNack);
    AcceptResponse unpickled = (AcceptResponse) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(acceptNack, unpickled);
  }

  @Test
  public void testPrepareResponsePickleUnpickleNoop() throws IOException {
    final var accept = new Accept((short) 4, 5L, new BallotNumber(6, (short) 7), NoOperation.NOOP);
    PrepareResponse prepareAck = new PrepareResponse(
        (short) 1, (short) 2,
        new PrepareResponse.Vote((short) 1, (short) 2, 3L, true, new BallotNumber(13, (short) 3)),
        Optional.of(accept), 1234213424L);
    byte[] pickled = PickleMsg.pickle(prepareAck);
    PrepareResponse unpickled = (PrepareResponse) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(prepareAck, unpickled);
  }

  @Test
  public void testPrepareResponsePickleUnpickleClientCommand() throws IOException {
    final var cmd = new Command("data".getBytes(StandardCharsets.UTF_8));
    final var accept = new Accept((short) 4, 5L, new BallotNumber(6, (short) 7), cmd);
    PrepareResponse prepareAck = new PrepareResponse(
        (short) 1, (short) 2,
        new PrepareResponse.Vote((short) 1, (short) 2, 3L, true, new BallotNumber(13, (short) 3)),
        Optional.of(accept), 1234213424L);
    byte[] pickled = PickleMsg.pickle(prepareAck);
    PrepareResponse unpickled = (PrepareResponse) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(prepareAck.journaledAccept(), unpickled.journaledAccept());
    assertEquals(prepareAck, unpickled);
  }

  @Test
  public void testPickleProgress() throws Exception {
    Progress progress = new Progress((short) 1, new BallotNumber(2, (short) 3), 4L);
    byte[] pickled = writeProgress(progress);
    Progress unpickled = readProgress(pickled);
    assertEquals(progress, unpickled);
  }

  @Test
  public void testPickFixed() throws Exception {
    Fixed fixed = new Fixed(
        (short) 3,
        5L, new BallotNumber(10, (short) 128));
    byte[] pickled = PickleMsg.pickle(fixed);
    Fixed unpickled = (Fixed) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(fixed, unpickled);
  }

  @Test
  public void testPickleCatchup() throws Exception {
    Catchup catchup = new Catchup((short) 2, (short) 3, 4L, new BallotNumber(5, (short) 6));
    byte[] pickled = PickleMsg.pickle(catchup);
    Catchup unpickled = (Catchup) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(catchup.from(), unpickled.from());
    assertEquals(catchup.to(), unpickled.to());
  }

  @Test
  public void testPickleCatchupResponse() throws Exception {
    final Accept[] accepts = {
        new Accept((short) 1, 2L, new BallotNumber(3, (short) 4), NoOperation.NOOP),
        new Accept((short) 5, 6L, new BallotNumber(7, (short) 8), NoOperation.NOOP)
    };
    CatchupResponse catchupResponse = new CatchupResponse((short) 1, (short) 2, Arrays.asList(accepts));
    byte[] pickled = PickleMsg.pickle(catchupResponse);
    CatchupResponse unpickled = (CatchupResponse) PickleMsg.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(catchupResponse.from(), unpickled.from());
    assertEquals(catchupResponse.to(), unpickled.to());
    assertArrayEquals(catchupResponse.accepts().toArray(), unpickled.accepts().toArray());
  }
}