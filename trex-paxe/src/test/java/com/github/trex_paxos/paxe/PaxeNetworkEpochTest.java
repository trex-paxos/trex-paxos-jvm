// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.BallotNumber;
import com.github.trex_paxos.NodeId;
import com.github.trex_paxos.msg.Fixed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaxeNetworkEpochTest {

  @Test
  void overlapAcceptsBothEpochs() throws Exception {
    byte[] epoch0Key = NetworkTestHarness.generateClusterPsk();
    byte[] epoch1Key = NetworkTestHarness.generateClusterPsk();

    var keyManager = new ClusterKeyManager(epoch0Key, (byte) 0);
    keyManager.installEpoch((byte) 1, epoch1Key);

    try (NetworkTestHarness harness = new NetworkTestHarness(epoch0Key)) {
      PaxeNetwork sender = harness.createNetwork((short) 1, keyManager).network();
      PaxeNetwork receiver = harness.createNetwork((short) 2, keyManager).network();
      harness.waitForNetworkEstablishment();

      CountDownLatch epoch0Latch = new CountDownLatch(1);
      CountDownLatch epoch1Latch = new CountDownLatch(1);
      receiver.subscribe(CONSENSUS.value(), (Fixed msg) -> {
        if (msg.slot() == 1) {
          epoch0Latch.countDown();
        } else if (msg.slot() == 2) {
          epoch1Latch.countDown();
        }
      }, "consensus");

      keyManager.setCurrentEpoch((byte) 0);
      sender.send(CONSENSUS.value(), new NodeId((short) 2),
          new Fixed((short) 1, 1, new BallotNumber((short) 0, 1, (short) 1)));
      assertTrue(epoch0Latch.await(1, TimeUnit.SECONDS));

      keyManager.setCurrentEpoch((byte) 1);
      sender.send(CONSENSUS.value(), new NodeId((short) 2),
          new Fixed((short) 1, 2, new BallotNumber((short) 0, 1, (short) 2)));
      assertTrue(epoch1Latch.await(1, TimeUnit.SECONDS));
    }
  }

  @Test
  void unknownEpochIsRejected() throws Exception {
    byte[] epoch0Key = NetworkTestHarness.generateClusterPsk();
    var keyManager = new ClusterKeyManager(epoch0Key, (byte) 0);

    try (NetworkTestHarness harness = new NetworkTestHarness(epoch0Key)) {
      PaxeNetwork sender = harness.createNetwork((short) 1, keyManager).network();
      PaxeNetwork receiver = harness.createNetwork((short) 2, keyManager).network();
      harness.waitForNetworkEstablishment();

      PaxePacket alienEpoch = PaxePacket.seal(
          new NodeId((short) 1),
          new NodeId((short) 2),
          CONSENSUS.value(),
          (byte) 7,
          new byte[]{1},
          epoch0Key);

      CountDownLatch latch = new CountDownLatch(1);
      receiver.subscribe(CONSENSUS.value(), msg -> latch.countDown(), "consensus");

      try (var channel = java.nio.channels.DatagramChannel.open()) {
        channel.send(
            java.nio.ByteBuffer.wrap(alienEpoch.toDatagram()),
            new java.net.InetSocketAddress("127.0.0.1", harness.portFor(new NodeId((short) 2))));
      }

      assertFalse(latch.await(300, TimeUnit.MILLISECONDS));

      sender.send(CONSENSUS.value(), new NodeId((short) 2),
          new Fixed((short) 1, 3, new BallotNumber((short) 0, 1, (short) 3)));
      assertTrue(latch.await(1, TimeUnit.SECONDS), "Receiver must stay operational");
    }
  }
}
