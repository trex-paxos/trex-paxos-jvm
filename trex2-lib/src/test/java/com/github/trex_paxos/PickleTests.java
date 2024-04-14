package com.github.trex_paxos;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PickleTests {

  @Test
  public void testPreparePickleUnpickle() throws IOException {
    Prepare prepare = new Prepare(
      4L, new BallotNumber(2, (byte) 3));
    byte[] pickled = Pickle.write(prepare);
    Prepare unpickled = (Prepare) Pickle.readTrexMessage(pickled);
    assertEquals(prepare, unpickled);
  }

  @Test
  public void testAcceptNoopPickleUnpickle() throws IOException {
    Accept accept = new Accept(4L, new BallotNumber(2, (byte) 3), NoOperation.NOOP);
    byte[] pickled = Pickle.write(accept);
    Accept unpickled = (Accept) Pickle.readTrexMessage(pickled);
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptPickleUnpickle() throws IOException {
    Command command = new Command("cmd", "data".getBytes(StandardCharsets.UTF_8));
    Accept accept = new Accept(4L, new BallotNumber(2, (byte) 3), command);
    byte[] pickled = Pickle.write(accept);
    Accept unpickled = (Accept) Pickle.readTrexMessage(pickled);
    assertEquals(accept, unpickled);
  }

  @Test
  public void testAcceptNackPickleUnpickle() throws IOException {
    AcceptResponse acceptNack = new AcceptResponse(
      new Vote((byte) 1, (byte) 2, 4L, true),
      new Progress((byte) 0,
        new BallotNumber(6, (byte) 7),
        11L,
        12L
      ));
    byte[] pickled = Pickle.write(acceptNack);
    AcceptResponse unpickled = (AcceptResponse) Pickle.readTrexMessage(pickled);
    assertEquals(acceptNack, unpickled);
  }

  @Test
  public void testPrepareResponsePickleUnpickle() throws IOException {
    PrepareResponse prepareAck = new PrepareResponse(
        new Vote((byte) 1, (byte) 2, 4L, true),
        Optional.of(new Accept(16L, new BallotNumber(14, (byte) 15), NoOperation.NOOP)),
        Optional.of(new CatchupResponse(17L, List.of(
            new Accept(18L, new BallotNumber(19, (byte) 20), NoOperation.NOOP),
            new Accept(21L, new BallotNumber(22, (byte) 23), NoOperation.NOOP)
        )))
    );
    byte[] pickled = Pickle.write(prepareAck);
    PrepareResponse unpickled = (PrepareResponse) Pickle.readTrexMessage(pickled);
    assertEquals(prepareAck, unpickled);
  }

  @Test
  public void testPickleProgress() throws Exception {
    Progress progress = new Progress((byte) 1, new BallotNumber(2, (byte) 3), 4L, 5L);
    byte[] pickled = Pickle.write(progress);
    Progress unpickled = (Progress) Pickle.readProgress(pickled);
    assertEquals(progress, unpickled);
  }

  @Test
  public void testPickCommit() throws Exception {
    Commit commit = new Commit(4L);
    byte[] pickled = Pickle.write(commit);
    Commit unpickled = (Commit) Pickle.readTrexMessage(pickled);
    assertEquals(commit, unpickled);
  }

  @Test
  public void testPickleCatchup() throws Exception {
    Catchup catchup = new Catchup((byte) 4, 5L);
    byte[] pickled = Pickle.write(catchup);
    Catchup unpickled = (Catchup) Pickle.readTrexMessage(pickled);
    assertEquals(catchup, unpickled);
  }

  @Test
  public void testPickleCatchupResponse() throws Exception {
    CatchupResponse catchupResponse = new CatchupResponse(4L, List.of(
        new Accept(5L, new BallotNumber(6, (byte) 7), NoOperation.NOOP),
        new Accept(8L, new BallotNumber(9, (byte) 10), NoOperation.NOOP)
    ));
    byte[] pickled = Pickle.write(catchupResponse);
    CatchupResponse unpickled = (CatchupResponse) Pickle.readTrexMessage(pickled);
    assertEquals(catchupResponse, unpickled);
  }
}
