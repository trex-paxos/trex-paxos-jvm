// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.msg.Accept;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CommandPicklerTests {

  @Test
  public void testPickleProgress() throws Exception {
    Progress progress = new Progress((short) 1, new BallotNumber((short) 2, 3, (short) 4), 5L);
    byte[] pickled = CommandPickler.writeProgress(progress);
    Progress unpickled = CommandPickler.readProgress(pickled);
    assertEquals(progress, unpickled);
  }

  @Test
  public void testPickleBallotNumber() throws Exception {
    BallotNumber ballotNumber = new BallotNumber((short) 1, 2, (short) 3);
    byte[] pickled = CommandPickler.write(ballotNumber);
    BallotNumber unpickled = CommandPickler.readBallotNumber(pickled);
    assertEquals(ballotNumber, unpickled);
  }

  @Test
  public void testPickleCommand() throws Exception {
    Command command = new Command(UUIDGenerator.generateUUID(), "data".getBytes(), (byte) 64);
    byte[] pickled = CommandPickler.write(command);
    final var unpickled = CommandPickler.readCommand(pickled);
    assertEquals(command, unpickled);
  }

    @Test
  public void testAcceptNoopPickleUnpickle() throws IOException {
    Accept accept = new Accept((short) 3, 4L, new BallotNumber((short) 0, 2, (short) 3), NoOperation.NOOP);
      byte[] pickled = CommandPickler.write(accept);
      Accept unpickled = CommandPickler.readAccept(pickled);
    assertEquals(accept, unpickled);
  }

    @Test
  public void testAcceptPickleUnpickleClientCommand() throws IOException {
    Command command = new Command( "data".getBytes(StandardCharsets.UTF_8));
    Accept accept = new Accept((short) 3, 4L, new BallotNumber((short) 0, 2, (short) 3), command);
      byte[] pickled = CommandPickler.write(accept);
      Accept unpickled = CommandPickler.readAccept(pickled);
    assertEquals(accept, unpickled);
  }
}
