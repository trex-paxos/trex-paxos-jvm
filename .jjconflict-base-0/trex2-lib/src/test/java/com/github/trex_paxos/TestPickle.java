package com.github.trex_paxos;

import com.github.trex_paxos.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestPickle {


    @Test
    public void testIdentifierPickleUnpickle() throws IOException {
        Identifier originalIdentifier = new Identifier((byte)1, new BallotNumber(2, (byte)3), 4L);

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
                new Identifier((byte)1, new BallotNumber(2, (byte)3), 4L));
        byte[] pickled = Pickle.write(prepare);
        Prepare unpickled = (Prepare) Pickle.read(pickled);
        assertEquals(prepare, unpickled);
    }

    @Test
    public void testPrepareAckPickleUnpickle() throws IOException {
        PrepareAck prepareAck = new PrepareAck(
                new Identifier((byte)1, new BallotNumber(2, (byte)2), 4L),
                (byte)5,
                new Progress(new BallotNumber(2, (byte)6), new Identifier((byte)7, new BallotNumber(8, (byte)9), 10L)),
                11L,
                12L,
                Optional.of(new Accept(new Identifier((byte)13, new BallotNumber(14, (byte)15), 16L))));
        byte[] pickled = Pickle.write(prepareAck);
        PrepareAck unpickled = (PrepareAck) Pickle.read(pickled);
        assertEquals(prepareAck, unpickled);
    }

    @Test
    public void testAcceptPickleUnpickle() throws IOException {
        Accept accept = new Accept(new Identifier((byte) 1, new BallotNumber(2, (byte) 3), 4L));
        byte[] pickled = Pickle.write(accept);
        Accept unpickled = (Accept) Pickle.read(pickled);
        assertEquals(accept, unpickled);
    }

    @Test
    public void testAcceptNackPickleUnpickle() throws IOException {
        AcceptNack acceptNack = new AcceptNack(
                new Identifier((byte) 1, new BallotNumber(2, (byte) 3), 4L),
                5,
                new Progress(new BallotNumber(6, (byte) 7), new Identifier((byte) 8, new BallotNumber(9, (byte) 10), 11L))
        );
        byte[] pickled = Pickle.write(acceptNack);
        AcceptNack unpickled = (AcceptNack) Pickle.read(pickled);
        assertEquals(acceptNack, unpickled);
    }
}
