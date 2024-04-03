package com.github.trex_paxos;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestPickle {


    @Test
    public void testIdentifierPickleUnpickle() throws IOException {
        Identifier originalIdentifier = new Identifier(new BallotNumber(2, (byte)3), 4L);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(byteArrayOutputStream);
        originalIdentifier.writeTo(dos);

        // Write the originalIdentifier to a byte array
        byte[] pickled = byteArrayOutputStream.toByteArray();

        ByteArrayInputStream bis = new ByteArrayInputStream(pickled);
        DataInputStream dis = new DataInputStream(bis);

        // Assert that the originalIdentifier and unpickledIdentifier are equal
        assertEquals(originalIdentifier, Identifier.readFrom(dis));
    }

    @Test
    public void testPreparePickleUnpickle() throws IOException {
        Prepare prepare = new Prepare(
                new Identifier(new BallotNumber(2, (byte)3), 4L));
        byte[] pickled = Pickle.write(prepare);
        Prepare unpickled = (Prepare) Pickle.read(pickled);
        assertEquals(prepare, unpickled);
    }

    @Test
    public void testPrepareResponsePickleUnpickle() throws IOException {
      PrepareResponse prepareAck = new PrepareResponse(
        new Vote((byte) 1, new Identifier(new BallotNumber(2, (byte) 3), 4L), true),
                new Progress(new BallotNumber(2, (byte)6), new Identifier(new BallotNumber(8, (byte)9), 10L)),
                11L,
                Optional.of(new Accept(new Identifier(new BallotNumber(14, (byte)15), 16L), NoOperation.NOOP)));
        byte[] pickled = Pickle.write(prepareAck);
      PrepareResponse unpickled = (PrepareResponse) Pickle.read(pickled);
        assertEquals(prepareAck, unpickled);
    }

    @Test
    public void testAcceptPickleUnpickle() throws IOException {
        Accept accept = new Accept(new Identifier(new BallotNumber(2, (byte) 3), 4L), NoOperation.NOOP);
        byte[] pickled = Pickle.write(accept);
        Accept unpickled = (Accept) Pickle.read(pickled);
        assertEquals(accept, unpickled);
    }

    @Test
    public void testAcceptNackPickleUnpickle() throws IOException {
      AcceptResponse acceptNack = new AcceptResponse(
        new Vote((byte) 1, new Identifier(new BallotNumber(2, (byte) 3), 4L), true),
                new Progress(new BallotNumber(6, (byte) 7), new Identifier(new BallotNumber(9, (byte) 10), 11L))
        );
        byte[] pickled = Pickle.write(acceptNack);
      AcceptResponse unpickled = (AcceptResponse) Pickle.read(pickled);
        assertEquals(acceptNack, unpickled);
    }
}
