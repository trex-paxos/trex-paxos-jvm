package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PickleTests {

  @Test
  public void testPreparePickleUnpickle() throws IOException {
    Prepare prepare = new Prepare((byte) 1, 2L, new BallotNumber(3, (byte) 4));
    byte[] pickled = Pickle.writeMessage(prepare);
    Prepare unpickled = (Prepare) Pickle.readMessage(pickled);
    assertEquals(prepare, unpickled);
  }

  @Test
  public void testAcceptNoopPickleUnpickle() throws IOException {
    Accept accept = new Accept((byte) 3, 4L, new BallotNumber(2, (byte) 3), NoOperation.NOOP);
    byte[] pickled = Pickle.writeMessage(accept);
    Accept unpickled = (Accept) Pickle.readMessage(pickled);
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptPickleUnpickleClientCommand() throws IOException {
    Command command = new Command("cmd", "data".getBytes(StandardCharsets.UTF_8));
    Accept accept = new Accept((byte) 3, 4L, new BallotNumber(2, (byte) 3), command);
    byte[] pickled = Pickle.writeMessage(accept);
    Accept unpickled = (Accept) Pickle.readMessage(pickled);
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptPickleUnpickleNoop() throws IOException {
    Accept accept = new Accept((byte) 3, 4L, new BallotNumber(2, (byte) 3), NoOperation.NOOP);
    byte[] pickled = Pickle.writeMessage(accept);
    Accept unpickled = (Accept) Pickle.readMessage(pickled);
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptNackPickleUnpickle() throws IOException {
    AcceptResponse acceptNack = new AcceptResponse(
        new Vote((byte) 1, (byte) 2, 4L, true, new BallotNumber(13, (byte) 3)),
      new Progress((byte) 0,
        new BallotNumber(6, (byte) 7),
        11L,
        12L
      ));
    byte[] pickled = Pickle.writeMessage(acceptNack);
    AcceptResponse unpickled = (AcceptResponse) Pickle.readMessage(pickled);
    assertEquals(acceptNack, unpickled);
  }

  @Test
  public void testPrepareResponsePickleUnpickleNoop() throws IOException {
    final var accept = new Accept((byte) 4, 5L, new BallotNumber(6, (byte) 7), NoOperation.NOOP);
    PrepareResponse prepareAck = new PrepareResponse(
        new Vote((byte) 1, (byte) 2, 3L, true, new BallotNumber(13, (byte) 3)),
        1234213424L, Optional.of(accept)
    );
    byte[] pickled = Pickle.writeMessage(prepareAck);
    PrepareResponse unpickled = (PrepareResponse) Pickle.readMessage(pickled);
    assertEquals(prepareAck, unpickled);
  }

  @Test
  public void testPrepareResponsePickleUnpickleClientCommand() throws IOException {
    final var cmd = new Command("cmd", "data".getBytes(StandardCharsets.UTF_8));
    final var accept = new Accept((byte) 4, 5L, new BallotNumber(6, (byte) 7), cmd);
    PrepareResponse prepareAck = new PrepareResponse(
        new Vote((byte) 1, (byte) 2, 3L, true, new BallotNumber(13, (byte) 3)),
        1234213424L, Optional.of(accept)
    );
    byte[] pickled = Pickle.writeMessage(prepareAck);
    PrepareResponse unpickled = (PrepareResponse) Pickle.readMessage(pickled);
    assertEquals(prepareAck.highestUncommitted(), unpickled.highestUncommitted());
    assertEquals(prepareAck, unpickled);
  }

  @Test
  public void testPickleProgress() throws Exception {
    Progress progress = new Progress((byte) 1, new BallotNumber(2, (byte) 3), 4L, 5L);
    byte[] pickled = Pickle.writeProgress(progress);
    Progress unpickled = Pickle.readProgress(pickled);
    assertEquals(progress, unpickled);
  }

  @Test
  public void testPickCommit() throws Exception {
    Commit commit = new Commit(5L, new BallotNumber(10, (byte) 128), (byte) 3);
    byte[] pickled = Pickle.writeMessage(commit);
    Commit unpickled = (Commit) Pickle.readMessage(pickled);
    assertEquals(commit, unpickled);
  }

  @Test
  public void testPickleCatchup() throws Exception {
    long[] slotGaps = {5, 7, 9};
    Catchup catchup = new Catchup((byte) 2, (byte) 3, slotGaps);
    byte[] pickled = Pickle.writeMessage(catchup);
    Catchup unpickled = (Catchup) Pickle.readMessage(pickled);
    assertEquals(catchup.from(), unpickled.from());
    assertEquals(catchup.to(), unpickled.to());
    assertArrayEquals(catchup.slotGaps(), unpickled.slotGaps());
  }

  @Test
  public void testPickleCatchupResponse() throws Exception {
    Accept[] accepts = {
        new Accept((byte) 1, 2L, new BallotNumber(3, (byte) 4), NoOperation.NOOP),
        new Accept((byte) 5, 6L, new BallotNumber(7, (byte) 8), NoOperation.NOOP)
    };
    CatchupResponse catchupResponse = new CatchupResponse((byte) 1, (byte) 2, Arrays.asList(accepts));
    byte[] pickled = Pickle.writeMessage(catchupResponse);
    CatchupResponse unpickled = (CatchupResponse) Pickle.readMessage(pickled);
    assertEquals(catchupResponse.from(), unpickled.from());
    assertEquals(catchupResponse.to(), unpickled.to());
    assertArrayEquals(catchupResponse.catchup().toArray(), unpickled.catchup().toArray());
  }
}
