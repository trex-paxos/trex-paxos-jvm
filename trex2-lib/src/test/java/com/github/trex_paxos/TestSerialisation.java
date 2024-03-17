package com.github.trex_paxos;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/**
 * Unit test for simple App.
 */
public class TestSerialisation {


    @Test
    public void testSerialisation() throws IOException {
        byte[] bytes1 = new String("test").getBytes();
        Accept accept = new Accept(new Identifier(2552, new BallotNumber(2, 2552), 4L), new Command("0", bytes1));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(bos);
        accept.writeTo(dataOutputStream);
        dataOutputStream.flush();
        byte[] bytes = bos.toByteArray();
        DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(bytes));
        Accept accept2 = Accept.readFrom(dataInputStream);
        assertThat(accept2).isEqualTo(accept); // Fix the method call
    }
}
