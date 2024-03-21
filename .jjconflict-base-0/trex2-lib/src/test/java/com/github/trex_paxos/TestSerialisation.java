package com.github.trex_paxos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Unit test for simple App.
 */
public class TestSerialisation {


    @Test
    public void testAccept() throws IOException {
        Accept accept = new Accept(new Identifier((byte) 1, new BallotNumber(2, (byte) 3), 4L));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(bos);
        accept.writeTo(dataOutputStream);
        dataOutputStream.flush();
        byte[] bytes = bos.toByteArray();
        DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(bytes));
        Accept accept2 = Accept.readFrom(dataInputStream);
        assertThat(accept2).isEqualTo(accept);
    }

    @Test
    public void testPrepare() throws IOException {
        Prepare prepare = new Prepare(new Identifier((byte) 1, new BallotNumber(2, (byte) 3), 4L));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(bos);
        prepare.writeTo(dataOutputStream);
        dataOutputStream.flush();
        byte[] bytes = bos.toByteArray();
        DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(bytes));
        Prepare prepare2 = Prepare.readFrom(dataInputStream);
        assertThat(prepare2).isEqualTo(prepare);
    }

    @Test
    public void testPrepareAck() throws IOException {
        byte[] bytes1 = "test".getBytes();
        PrepareAck prepareAck = new PrepareAck(
                new Identifier((byte) 1, new BallotNumber(2, (byte) 3), 4L),
                (byte) 4,
                new Progress(new BallotNumber(5, (byte) 6), new Identifier((byte) 7, new BallotNumber(8, (byte) 9), 10L)),
                11L,
                12L,
                Optional.of(new Accept(new Identifier((byte) 13, new BallotNumber(14, (byte) 15), 16L)
                )));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(bos);
        prepareAck.writeTo(dataOutputStream);
        dataOutputStream.flush();
        byte[] bytes = bos.toByteArray();
        DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(bytes));
        PrepareAck prepareAck2 = PrepareAck.readFrom(dataInputStream);
        assertThat(prepareAck2).isEqualTo(prepareAck);
    }

    @Test
    public void testPrepareNackSerialisation() throws IOException {
        PrepareNack prepareNack = new PrepareNack(
                new Identifier((byte) 1, new BallotNumber(2, (byte) 3), 4L),
                4,
                new Progress(new BallotNumber(5, (byte) 6), new Identifier((byte) 7, new BallotNumber(8, (byte) 9), 10L)),
                11L,
                12L);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(bos);
        prepareNack.writeTo(dataOutputStream);
        dataOutputStream.flush();
        byte[] bytes = bos.toByteArray();
        DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(bytes));
        PrepareNack prepareNack2 = PrepareNack.readFrom(dataInputStream);
        assertThat(prepareNack2).isEqualTo(prepareNack);
    }

    @Test
    public void testAcceptNackSerialisation() throws IOException {
        AcceptNack acceptNack = new AcceptNack(
                new Identifier((byte) 1, new BallotNumber(2, (byte) 3), 4L),
                5,
                new Progress(new BallotNumber(6, (byte) 7), new Identifier((byte) 8, new BallotNumber(9, (byte) 10), 11L)));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(bos);
        acceptNack.writeTo(dataOutputStream);
        dataOutputStream.flush();
        byte[] bytes = bos.toByteArray();
        DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(bytes));
        AcceptNack acceptNack2 = AcceptNack.readFrom(dataInputStream);
        assertThat(acceptNack2).isEqualTo(acceptNack);
    }


}
