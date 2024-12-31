package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class PaxeNetworkTest {

    private PaxeNetwork network1;
    private PaxeNetwork network2;

    @BeforeEach
    public void setup() throws Exception {
        // Create and retain DatagramSockets to avoid race conditions
        DatagramSocket socket1 = new DatagramSocket(0);
        DatagramSocket socket2 = new DatagramSocket(0);
    
        int port1 = socket1.getLocalPort();
        int port2 = socket2.getLocalPort();
    
        // Close sockets after fetching ports
        socket1.close();
        socket2.close();
    
        NodeId node1 = new NodeId((byte) 1);
        NodeId node2 = new NodeId((byte) 2);
    
        // Cluster membership mapping
        ClusterMembership membership = new ClusterMembership(Map.of(
            node1, new NetworkAddress.InetAddress("127.0.0.1", port1),
            node2, new NetworkAddress.InetAddress("127.0.0.1", port2)
        ));
    
        // Shared session keys (dummy keys for simplicity)
        byte[] sharedKey = "1234567890123456".getBytes(); // 16-byte AES key
        Map<SessionKeyPair, byte[]> sessionKeys = Map.of(
            new SessionKeyPair(node1, node2), sharedKey
        );
    
        // Initialize networks with Supplier<ClusterMembership>
        network1 = new PaxeNetwork(port1, node1, () -> membership, sessionKeys);
        network2 = new PaxeNetwork(port2, node2, () -> membership, sessionKeys);
    }
    
    @AfterEach
    public void teardown() throws Exception {
        if (network1 != null) network1.close();
        if (network2 != null) network2.close();
    }

    @Test
    public void testSendAndReceiveMessages() throws Exception {
        // Define channels
        Channel channel = new Channel((byte) 0);

        // Prepare messages
        PaxeMessage messageFromNode1 = new PaxeMessage(
            new NodeId((byte) 1),
            new NodeId((byte) 2),
            channel,
            "Hello from Node 1".getBytes()
        );

        PaxeMessage messageFromNode2 = new PaxeMessage(
            new NodeId((byte) 2),
            new NodeId((byte) 1),
            channel,
            "Hello from Node 2".getBytes()
        );

        // Encrypt and send messages
        network1.encryptAndSend(messageFromNode1, "1234567890123456".getBytes());
        network2.encryptAndSend(messageFromNode2, "1234567890123456".getBytes());

        // Receive messages
        PaxeMessage receivedByNode2 = network2.receive(channel);
        PaxeMessage receivedByNode1 = network1.receive(channel);

        // Verify received messages
        assertArrayEquals(messageFromNode1.payload(), receivedByNode2.payload());
        assertArrayEquals(messageFromNode2.payload(), receivedByNode1.payload());
    }
}
