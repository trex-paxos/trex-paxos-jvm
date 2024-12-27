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

import com.github.trex_paxos.msg.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static com.github.trex_paxos.Pickle.readProgress;
import static com.github.trex_paxos.Pickle.writeProgress;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PickleTests {

  @Test
  public void testPreparePickleUnpickle() throws IOException {
    Prepare prepare = new Prepare((byte) 1, 2L, new BallotNumber(3, (byte) 4));
    byte[] pickled = PicklePAXE.pickle(prepare);
    Prepare unpickled = (Prepare) PicklePAXE.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(prepare, unpickled);
  }

  @Test
  public void testAcceptNoopPickleUnpickle() throws IOException {
    Accept accept = new Accept((byte) 3, 4L, new BallotNumber(2, (byte) 3), NoOperation.NOOP);
    byte[] pickled = PicklePAXE.pickle(accept);
    Accept unpickled = (Accept) PicklePAXE.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptPickleUnpickleClientCommand() throws IOException {
    Command command = new Command("cmd", "data".getBytes(StandardCharsets.UTF_8));
    Accept accept = new Accept((byte) 3, 4L, new BallotNumber(2, (byte) 3), command);
    byte[] pickled = PicklePAXE.pickle(accept);
    Accept unpickled = (Accept) PicklePAXE.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptPickleUnpickleNoop() throws IOException {
    Accept accept = new Accept((byte) 3, 4L, new BallotNumber(2, (byte) 3), NoOperation.NOOP);
    byte[] pickled = PicklePAXE.pickle(accept);
    Accept unpickled = (Accept) PicklePAXE.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptNackPickleUnpickle() throws IOException {
    AcceptResponse acceptNack = new AcceptResponse(
        (byte) 1, (byte) 2,
        new AcceptResponse.Vote((byte) 1, (byte) 2, 4L, true),
        11L);
    byte[] pickled = PicklePAXE.pickle(acceptNack);
    AcceptResponse unpickled = (AcceptResponse) PicklePAXE.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(acceptNack, unpickled);
  }

  @Test
  public void testPrepareResponsePickleUnpickleNoop() throws IOException {
    final var accept = new Accept((byte) 4, 5L, new BallotNumber(6, (byte) 7), NoOperation.NOOP);
    PrepareResponse prepareAck = new PrepareResponse(
        (byte) 1, (byte) 2,
        new PrepareResponse.Vote((byte) 1, (byte) 2, 3L, true, new BallotNumber(13, (byte) 3)),
        Optional.of(accept), 1234213424L
    );
    byte[] pickled = PicklePAXE.pickle(prepareAck);
    PrepareResponse unpickled = (PrepareResponse) PicklePAXE.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(prepareAck, unpickled);
  }

  @Test
  public void testPrepareResponsePickleUnpickleClientCommand() throws IOException {
    final var cmd = new Command("cmd", "data".getBytes(StandardCharsets.UTF_8));
    final var accept = new Accept((byte) 4, 5L, new BallotNumber(6, (byte) 7), cmd);
    PrepareResponse prepareAck = new PrepareResponse(
        (byte) 1, (byte) 2,
        new PrepareResponse.Vote((byte) 1, (byte) 2, 3L, true, new BallotNumber(13, (byte) 3)),
        Optional.of(accept), 1234213424L
    );
    byte[] pickled = PicklePAXE.pickle(prepareAck);
    PrepareResponse unpickled = (PrepareResponse) PicklePAXE.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(prepareAck.journaledAccept(), unpickled.journaledAccept());
    assertEquals(prepareAck, unpickled);
  }

  @Test
  public void testPickleProgress() throws Exception {
    Progress progress = new Progress((byte) 1, new BallotNumber(2, (byte) 3), 4L);
    byte[] pickled = writeProgress(progress);
    Progress unpickled = readProgress(pickled);
    assertEquals(progress, unpickled);
  }

  @Test
  public void testPickFixed() throws Exception {
    Fixed fixed = new Fixed(
        (byte) 3,
        5L, new BallotNumber(10, (byte) 128)
    );
    byte[] pickled = PicklePAXE.pickle(fixed);
    Fixed unpickled = (Fixed) PicklePAXE.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(fixed, unpickled);
  }

  @Test
  public void testPickleCatchup() throws Exception {
    Catchup catchup = new Catchup((byte) 2, (byte) 3, 4L, new BallotNumber(5, (byte) 6));
    byte[] pickled = PicklePAXE.pickle(catchup);
    Catchup unpickled = (Catchup) PicklePAXE.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(catchup.from(), unpickled.from());
    assertEquals(catchup.to(), unpickled.to());
  }

  @Test
  public void testPickleCatchupResponse() throws Exception {
    final Accept[] accepts = {
        new Accept((byte) 1, 2L, new BallotNumber(3, (byte) 4), NoOperation.NOOP),
        new Accept((byte) 5, 6L, new BallotNumber(7, (byte) 8), NoOperation.NOOP)
    };
    CatchupResponse catchupResponse = new CatchupResponse((byte) 1, (byte) 2, Arrays.asList(accepts));
    byte[] pickled = PicklePAXE.pickle(catchupResponse);
    CatchupResponse unpickled = (CatchupResponse) PicklePAXE.unpickle(java.nio.ByteBuffer.wrap(pickled));
    assertEquals(catchupResponse.from(), unpickled.from());
    assertEquals(catchupResponse.to(), unpickled.to());
    assertArrayEquals(catchupResponse.accepts().toArray(), unpickled.accepts().toArray());
  }
}
