package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SpecificTests {
  static {
    if (System.getProperty("NO_LOGGING") != null && System.getProperty("NO_LOGGING").equals("true")) {
      Logger.getLogger("").setLevel(Level.OFF);
    } else {
      LoggerConfig.initialize();
    }
  }

  final QuorumStrategy threeNodeQuorum = new FixedQuorumStrategy(3);

  /// This is a little subtle. The catchup response should not violate the invariants of the system.
  /// When a node is isolated it will make a self promise that may be higher than the current leader.
  /// The current leader can commit values for slots that the isolated node is not aware of.
  /// When the isolated node rejoins it will backdown and request a catchup. When the response is received
  /// the node should respect the invariant and ignore any values for slots that it has already committed.
  /// it should also learn the value for the slot that it has not yet committed and accept that value even though
  /// it has self promised a higher ballot number.
  @Test
  public void testCatchupDoesNotViolateInvariantsYetDoesLearnDespiteHigherSelfPromise() {

    // Given that node 1 has accepted a value at slot 1 and has made a very high self promise
    final var nodeId1 = (byte) 1;
    final var journal = new Simulation.TransparentJournal((byte) 1);
    final var acceptPreviouslyCommittedSlot1 = new Accept((byte) 1, 1L, new BallotNumber(1, (byte) 1), new Command("cmd", "data".getBytes()));
    final var higherSelfPromiseNumber = new BallotNumber(1000, (byte) 1);
    TrexNode node = new TrexNode(Level.INFO, nodeId1, threeNodeQuorum, journal) {{
      this.journal.journalAccept(acceptPreviouslyCommittedSlot1);
      this.progress = new Progress(nodeIdentifier, higherSelfPromiseNumber, 1L, 1L);
    }};

    // When node 2 sends a catchup response that has commited values made under a previous leaders ballot number
    // And where the actual commited message at slot one is different to the one that node 1 thinks is already committed.
    final var nodeId2 = (byte) 2;
    final var ballotNumber2 = new BallotNumber(2, (byte) 2);
    final var ignoreAcceptSlot1 = new Accept(nodeId2, 1L, ballotNumber2, new Command("cmd", "data2".getBytes()));
    final var freshAcceptSlot2 = new Accept(nodeId2, 2L, ballotNumber2, new Command("cmd", "data3".getBytes()));
    final var catchUpResponse = new CatchupResponse(nodeId1, nodeId2, List.of(ignoreAcceptSlot1, freshAcceptSlot2), new Commit(nodeId2, ballotNumber2, 2L));

    // And node1 processes the message
    node.paxos(catchUpResponse);

    // Then the committed value should not have changed after processing the catchup.
    assertEquals(acceptPreviouslyCommittedSlot1, journal.fakeJournal.get(1L), "The committed value should not have changed after processing the catchup.");
    // And the node should accept the second slot value even having made a higher self-promise
    assertEquals(freshAcceptSlot2, journal.fakeJournal.get(2L), "The node should accepted the second slot value even having made a higher self-promise");
    // And the node should not have updated its progress  ballot number
    assertEquals(higherSelfPromiseNumber, node.progress.highestPromised(), "The node should not have updated its progress to the new ballot number");
    // And the node should have updated the progress committed index// And the node should have updated the progress committed index
    assertEquals(2L, node.progress.highestCommittedIndex(), "The node should have updated the progress committed index");
    
  }
  

}
