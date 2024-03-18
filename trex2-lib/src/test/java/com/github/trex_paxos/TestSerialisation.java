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
        byte[] bytes1 = "test".getBytes();
        Accept accept = new Accept(new Identifier(2552, new BallotNumber(2, 2552), 4L), new Command("0", bytes1));
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
        Prepare prepare = new Prepare(new Identifier(2552, new BallotNumber(2, 2552), 4L));
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
                new Identifier(2552, new BallotNumber(2, 2552), 4L),
                2552,
                new Progress(new BallotNumber(2, 2552), new Identifier(2552, new BallotNumber(2, 2552), 4L)),
                4L,
                4L,
                Optional.of(new Accept(new Identifier(2552, new BallotNumber(2, 2552), 4L),
                        new Command("0", bytes1))));
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
        byte[] bytes1 = new String("test").getBytes();
        PrepareNack prepareNack = new PrepareNack(
                new Identifier(2552, new BallotNumber(2, 2552), 4L),
                2552,
                new Progress(new BallotNumber(2, 2552), new Identifier(2552, new BallotNumber(2, 2552), 4L)),
                4L,
                4L);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(bos);
        prepareNack.writeTo(dataOutputStream);
        dataOutputStream.flush();
        byte[] bytes = bos.toByteArray();
        DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(bytes));
        PrepareNack prepareNack2 = PrepareNack.readFrom(dataInputStream);
        assertThat(prepareNack2).isEqualTo(prepareNack);
    }
}
