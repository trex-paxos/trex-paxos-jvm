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

import com.github.trex_paxos.msg.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpecificTests {
  static {
    if (System.getProperty("NO_LOGGING") != null && System.getProperty("NO_LOGGING").equals("true")) {
      Logger.getLogger("").setLevel(Level.OFF);
    } else {
      LoggerConfig.initialize();
    }
  }

  final QuorumStrategy threeNodeQuorum = new SimpleMajority(3);

  /// This is a little subtle. The catchup response should not violate the invariants of the system.
  /// When a node is isolated it will make a self promise that may be higher than the current leader.
  /// The current leader can fix values for slots that the isolated node is not aware of.
  /// When the isolated node rejoins it will abdicate and request a catchup. When the response is received
  /// the node should respect the invariant and ignore any values for slots that it has already been fixed.
  /// it should also learn the value for the slot that it has not yet fixed and accept that value even though
  /// it has self promised a higher ballot number.
  @Test
  public void testCatchupDoesNotViolateInvariantsYetDoesLearnDespiteHigherSelfPromise() {

    // Given that node 1 has accepted a value at slot 1 and has made a very high self promise
    final var nodeId1 = (byte) 1;
    final var journal = new TransparentJournal((byte) 1);
    final var acceptPreviouslyFixedSlot1 = new Accept((byte) 1, 1L, new BallotNumber(1, (byte) 1), new Command( "data".getBytes()));
    final var higherSelfPromiseNumber = new BallotNumber(1000, (byte) 1);
    TrexNode node = new TrexNode(Level.INFO, nodeId1, threeNodeQuorum, journal) {{
      this.journal.writeAccept(acceptPreviouslyFixedSlot1);
      this.progress = new Progress(nodeIdentifier, higherSelfPromiseNumber, 1L);
    }};

    // When node 2 sends a catchup response that has fixed values made under a previous leaders ballot number
    // And where the actual fixed message at slot one is different to the one that node 1 thinks is already fixed.
    final var nodeId2 = (byte) 2;
    final var ballotNumber2 = new BallotNumber(2, (byte) 2);
    final var ignoreAcceptSlot1 = new Accept(nodeId2, 1L, ballotNumber2, new Command("data2".getBytes()));
    final var freshAcceptSlot2 = new Accept(nodeId2, 2L, ballotNumber2, new Command("data3".getBytes()));
    final var catchUpResponse = new CatchupResponse(nodeId1, nodeId2, List.of(ignoreAcceptSlot1, freshAcceptSlot2));

    // And node1 processes the message
    node.paxos(catchUpResponse);

    // Then the fixed value should not have changed after processing the catchup.
    assertEquals(acceptPreviouslyFixedSlot1, journal.fakeJournal.get(1L), "The fixed value should not have changed after processing the catchup.");
    // And the node should accept the second slot value even having made a higher self-promise
    assertEquals(freshAcceptSlot2, journal.fakeJournal.get(2L), "The node should accepted the second slot value even having made a higher self-promise");
    // And the node should not have updated its progress  ballot number
    assertEquals(higherSelfPromiseNumber, node.progress.highestPromised(), "The node should not have updated its progress to the new ballot number");
    // And the node should have updated the progress fixed index
    assertEquals(2L, node.progress.highestFixedIndex(), "The node should have updated the progress fixed index");
  }

  /// An isolated node will increment its term when it times out and will be higher than a stable leader.
  /// When the isolated node rejoins it will request a catchup. The leader will respond with the current term.
  /// The leader must also increment its term to be higher than the isolated node. This is because otherwise
  /// the isolated noe will not accept the term and will keep on asking for catch-ups which is wasted network
  /// traffic.
  @Test
  public void testCatchupWithHigherBallotNumberAndLowerFixedSlotCausesLeaderToIncrementTheTerm() {
    // Given leader node 1
    final var nodeId1 = (byte) 1;
    final var originalNumber = new BallotNumber(1, nodeId1);
    final var journal = new TransparentJournal(nodeId1);
    final var acceptPreviouslyFixedSlot1 = new Accept((byte) 1, 1L, new BallotNumber(1, (byte) 1), new Command( "data".getBytes()));
    TrexNode node = new TrexNode(Level.INFO, nodeId1, threeNodeQuorum, journal) {{
      this.progress = new Progress(nodeIdentifier, originalNumber, 1L);
      this.journal.writeAccept(acceptPreviouslyFixedSlot1);
      this.setRole(TrexRole.LEAD);
      this.term = originalNumber;
    }};

    assert node.progress.highestPromised().equals(originalNumber);

    // When we get a higher self promise catchup request
    final var nodeId2 = (byte) 2;
    final var higherSelfPromiseNumber = new BallotNumber(originalNumber.counter() + 1, nodeId2);

    assert node.progress.highestPromised().lessThan(higherSelfPromiseNumber);

    final var catchup = new Catchup(nodeId2, nodeId1, 1L, higherSelfPromiseNumber);
    node.paxos(catchup);

    final var nextAccept = node.nextAcceptMessage(new Command("data2".getBytes()));

    final var finalHighestPromised = nextAccept.number();
    assertTrue(finalHighestPromised.greaterThan(originalNumber));
    assertTrue(finalHighestPromised.greaterThan(higherSelfPromiseNumber));
    assertTrue(finalHighestPromised.greaterThan(originalNumber));
  }

  // FIXME make sure you test explicitly all the abdication scenarios

  // FIXME other tests around making sure fixed messages are issued for every accept
}
