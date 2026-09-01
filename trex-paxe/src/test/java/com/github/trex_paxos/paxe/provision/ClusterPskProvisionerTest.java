// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe.provision;

import com.github.trex_paxos.paxe.ClusterKeyManager;
import com.github.trex_paxos.paxe.NetworkTestHarness;
import com.github.trex_paxos.paxe.PaxePacket;
import com.github.trex_paxos.NodeId;
import com.github.trex_paxos.network.Channel;
import org.bouncycastle.tls.PskKeyExchangeMode;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsFatalAlertReceived;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class ClusterPskProvisionerTest {

  private ClusterPskProvisionerServer server;

  @AfterEach
  void tearDown() throws Exception {
    if (server != null) {
      server.close();
    }
  }

  @Test
  void provisionsSameClusterPskToMultipleMembers() throws Exception {
    byte[] clusterPsk = NetworkTestHarness.generateClusterPsk();
    byte[] bootstrapKey = randomBytes(BootstrapPsk.KEY_SIZE);
    var bootstrap = new BootstrapPsk("test-cluster", bootstrapKey);

    server = startServer(bootstrap, clusterPsk, (byte) 0);
    var address = new InetSocketAddress("127.0.0.1", server.port());

    byte[] memberOne = ClusterPskProvisionerClient.fetchClusterPsk(bootstrap, address, (byte) 0);
    byte[] memberTwo = ClusterPskProvisionerClient.fetchClusterPsk(bootstrap, address, (byte) 0);

    assertArrayEquals(clusterPsk, memberOne);
    assertArrayEquals(clusterPsk, memberTwo);
  }

  @Test
  void provisionAndInstallDoesNotMutateKeysOnFailure() throws Exception {
    byte[] clusterPsk = NetworkTestHarness.generateClusterPsk();
    var bootstrap = new BootstrapPsk("test-cluster", randomBytes(BootstrapPsk.KEY_SIZE));
    var wrongBootstrap = new BootstrapPsk("test-cluster", randomBytes(BootstrapPsk.KEY_SIZE));
    byte[] originalPsk = NetworkTestHarness.generateClusterPsk();
    var keyManager = new ClusterKeyManager(originalPsk);

    server = startServer(bootstrap, clusterPsk, (byte) 0);
    var address = new InetSocketAddress("127.0.0.1", server.port());

    assertThrows(SecurityException.class,
        () -> ClusterPskProvisionerClient.provisionAndInstall(wrongBootstrap, keyManager, address, (byte) 0));
    assertArrayEquals(originalPsk, keyManager.keyForEpoch((byte) 0));
  }

  @Test
  void pskOnlyModeIsRejected() throws Exception {
    byte[] clusterPsk = NetworkTestHarness.generateClusterPsk();
    var bootstrap = new BootstrapPsk("test-cluster", randomBytes(BootstrapPsk.KEY_SIZE));

    server = startServer(bootstrap, clusterPsk, (byte) 0);
    var address = new InetSocketAddress("127.0.0.1", server.port());

    try (Socket socket = new Socket()) {
      socket.connect(address, 5_000);
      var protocol = new TlsClientProtocol(socket.getInputStream(), socket.getOutputStream());
      assertThrows(TlsFatalAlertReceived.class,
          () -> protocol.connect(ProvisionerTls.client(bootstrap, new short[]{PskKeyExchangeMode.psk_ke})));
    }
  }

  @Test
  void paxeRoundTripAfterProvisioning() throws Exception {
    byte[] clusterPsk = NetworkTestHarness.generateClusterPsk();
    var bootstrap = new BootstrapPsk("test-cluster", randomBytes(BootstrapPsk.KEY_SIZE));

    server = startServer(bootstrap, clusterPsk, (byte) 0);
    var address = new InetSocketAddress("127.0.0.1", server.port());

    var senderKeys = new ClusterKeyManager(NetworkTestHarness.generateClusterPsk());
    var receiverKeys = new ClusterKeyManager(NetworkTestHarness.generateClusterPsk());

    ClusterPskProvisionerClient.provisionAndInstall(bootstrap, senderKeys, address, (byte) 0);
    ClusterPskProvisionerClient.provisionAndInstall(bootstrap, receiverKeys, address, (byte) 0);

    assertArrayEquals(senderKeys.keyForEpoch((byte) 0), receiverKeys.keyForEpoch((byte) 0));

    byte[] plaintext = "paxe-round-trip".getBytes();
    PaxePacket packet = PaxePacket.seal(
        new NodeId((short) 1),
        new NodeId((short) 2),
        new Channel(1),
        (byte) 0,
        plaintext,
        senderKeys.keyForEpoch((byte) 0));

    assertArrayEquals(plaintext, packet.decrypt(receiverKeys.keyForEpoch((byte) 0)));
  }

  private ClusterPskProvisionerServer startServer(BootstrapPsk bootstrap, byte[] clusterPsk, byte epoch)
      throws Exception {
    return new ClusterPskProvisionerServer(
        bootstrap,
        requestedEpoch -> requestedEpoch == epoch ? clusterPsk.clone() : null,
        new InetSocketAddress("127.0.0.1", 0));
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }
}
