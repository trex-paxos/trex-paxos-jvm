package com.github.trex_paxos;

import org.junit.jupiter.api.Test;

import java.io.*;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Unit test for simple App.
 */
public class SerialisationTests {


  @Test
  public void testAccept() throws IOException {
    Accept accept = new Accept((byte) 4, 4L, new BallotNumber(2, (byte) 3), NoOperation.NOOP);
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
    Prepare prepare = new Prepare((byte) 4, 4L, new BallotNumber(2, (byte) 3));
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dataOutputStream = new DataOutputStream(bos);
    prepare.writeTo(dataOutputStream);
    dataOutputStream.flush();
    byte[] bytes = bos.toByteArray();
    DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(bytes));
    Prepare prepare2 = Prepare.readFrom(dataInputStream);
    assertThat(prepare2).isEqualTo(prepare);
  }
}
