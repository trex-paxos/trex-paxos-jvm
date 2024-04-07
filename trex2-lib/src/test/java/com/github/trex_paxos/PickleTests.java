package com.github.trex_paxos;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PickleTests {

  @Test
  public void testPreparePickleUnpickle() throws IOException {
    Prepare prepare = new Prepare(
      4L, new BallotNumber(2, (byte) 3));
    byte[] pickled = Pickle.write(prepare);
    Prepare unpickled = (Prepare) Pickle.read(pickled);
    assertEquals(prepare, unpickled);
  }

  @Test
  public void testPrepareResponsePickleUnpickle() throws IOException {
    PrepareResponse prepareAck = new PrepareResponse(
      new Vote((byte) 1, (byte) 2, 4L, true),
      new Progress((byte) 0, new BallotNumber(2, (byte) 6), 10L,
        11L),
      Optional.of(new Accept(16L, new BallotNumber(14, (byte) 15), NoOperation.NOOP)),
      List.of(new Accept(19L, new BallotNumber(17, (byte) 18), NoOperation.NOOP))
    );
    byte[] pickled = Pickle.write(prepareAck);
    PrepareResponse unpickled = (PrepareResponse) Pickle.read(pickled);
    assertEquals(prepareAck, unpickled);
  }

  @Test
  public void testAcceptPickleUnpickle() throws IOException {
    Accept accept = new Accept(4L, new BallotNumber(2, (byte) 3), NoOperation.NOOP);
    byte[] pickled = Pickle.write(accept);
    Accept unpickled = (Accept) Pickle.read(pickled);
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
    AcceptResponse unpickled = (AcceptResponse) Pickle.read(pickled);
    assertEquals(acceptNack, unpickled);
  }
}
