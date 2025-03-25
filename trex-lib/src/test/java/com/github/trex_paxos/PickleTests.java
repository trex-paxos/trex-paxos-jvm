/*
 * Copyright 2024 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos;

import org.junit.jupiter.api.Test;

import com.github.trex_paxos.msg.Accept;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PickleTests {

  @Test
  public void testPickleProgress() throws Exception {
    Progress progress = new Progress((short) 1, new BallotNumber((short) 2, 3, (short) 4), 5L);
    byte[] pickled = Pickle.writeProgress(progress);
    Progress unpickled = Pickle.readProgress(pickled);
    assertEquals(progress, unpickled);
  }

  @Test
  public void testPickleBallotNumber() throws Exception {
    BallotNumber ballotNumber = new BallotNumber((short) 1, 2, (short) 3);
    byte[] pickled = Pickle.write(ballotNumber);
    BallotNumber unpickled = Pickle.readBallotNumber(pickled);
    assertEquals(ballotNumber, unpickled);
  }

  @Test
  public void testPickleCommand() throws Exception {
    Command command = new Command((byte) 64, UUIDGenerator.generateUUID(), "data".getBytes());
    byte[] pickled = Pickle.write(command);
    final var unpickled = Pickle.readCommand(pickled);
    assertEquals(command, unpickled);
  }

    @Test
  public void testAcceptNoopPickleUnpickle() throws IOException {
    Accept accept = new Accept((short) 3, 4L, new BallotNumber((short) 0, 2, (short) 3), NoOperation.NOOP);
    byte[] pickled = Pickle.write(accept);
    Accept unpickled = Pickle.readAccept(pickled);
    assertEquals(accept, unpickled);
  }

    @Test
  public void testAcceptPickleUnpickleClientCommand() throws IOException {
    Command command = new Command( "data".getBytes(StandardCharsets.UTF_8));
    Accept accept = new Accept((short) 3, 4L, new BallotNumber((short) 0, 2, (short) 3), command);
    byte[] pickled = Pickle.write(accept);
    Accept unpickled = Pickle.readAccept(pickled);
    assertEquals(accept, unpickled);
  }
}
