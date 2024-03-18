package com.github.trex_paxos;

import com.github.trex_paxos.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestPickle {

    @Test
    public void testPreparePickleUnpickle() throws IOException {
        Prepare prepare = new Prepare(
                new Identifier(2552, new BallotNumber(2, 2552), 4L));
        byte[] pickled = Pickle.write(prepare);
        Prepare unpickled = (Prepare) Pickle.read(pickled);
        assertEquals(prepare, unpickled);
    }

    @Test
    public void testPrepareAckPickleUnpickle() throws IOException {
        PrepareAck prepareAck = new PrepareAck(
                new Identifier(2552, new BallotNumber(2, 2552), 4L),
                2552,
                new Progress(new BallotNumber(2, 2552), new Identifier(2552, new BallotNumber(2, 2552), 4L)),
                4L,
                4L,
                Optional.of(new Accept(new Identifier(2552, new BallotNumber(2, 2552), 4L))));
        byte[] pickled = Pickle.write(prepareAck);
        PrepareAck unpickled = (PrepareAck) Pickle.read(pickled);
        assertEquals(prepareAck, unpickled);
    }

    @Test
    public void testAcceptPickleUnpickle() throws IOException {
        Accept accept = new Accept(new Identifier(2552, new BallotNumber(2, 2552), 4L));
        byte[] pickled = Pickle.write(accept);
        Accept unpickled = (Accept) Pickle.read(pickled);
        assertEquals(accept, unpickled);
    }

    @Test
    public void testAcceptNackPickleUnpickle() throws IOException {
        AcceptNack acceptNack = new AcceptNack(
                new Identifier(2552, new BallotNumber(2, 2552), 4L),
                2552,
                new Progress(new BallotNumber(2, 2552), new Identifier(2552, new BallotNumber(2, 2552), 4L))
        );
        byte[] pickled = Pickle.write(acceptNack);
        AcceptNack unpickled = (AcceptNack) Pickle.read(pickled);
        assertEquals(acceptNack, unpickled);
    }
}
