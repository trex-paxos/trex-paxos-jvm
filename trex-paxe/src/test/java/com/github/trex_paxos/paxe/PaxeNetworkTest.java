package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.trex_paxos.paxe.SRPUtils.Constants;

import java.net.DatagramSocket;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PaxeNetworkTest {

        private static final Logger LOGGER = Logger.getLogger(PaxeNetworkTest.class.getName());

        final static String hexN = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" + //
                        "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" + //
                        "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" + //
                        "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" + //
                        "FD5138FE8376435B9FC61D2FC0EB06E3";

        final static String hexG = "2";

        final static String hexV = "7E273DE8696FFC4F4E337D05B4B375BEB0DDE1569E8FA00A9886D812" +
                        "9BADA1F1822223CA1A605B530E379BA4729FDC59F105B4787E5186F5" +
                        "C671085A1447B52A48CF1970B4FB6F8400BBF4CEBFBB168152E08AB5" +
                        "EA53D15C1AFF87B2B9DA6E04E058AD51CC72BFC9033B564E26480D78" +
                        "E955A5E29E7AB245DB2BE315E2099AFB";

        final static Constants constants = new Constants(hexN, hexG);

        private PaxeNetwork network1;
        private PaxeNetwork network2;

        @BeforeAll
        static void setupLogging() {

            final var logLevel = System.getProperty("java.util.logging.ConsoleHandler.level", "WARNING");
            final Level level = Level.parse(logLevel);
            // Configure PaxeNetworkTest logger
            LOGGER.setLevel(level);
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(level);
            LOGGER.addHandler(consoleHandler);
    
            // Configure SessionKeyManager logger
            Logger sessionKeyManagerLogger = Logger.getLogger(SessionKeyManager.class.getName());
            sessionKeyManagerLogger.setLevel(level);
            ConsoleHandler skmHandler = new ConsoleHandler();
            skmHandler.setLevel(level);
            sessionKeyManagerLogger.addHandler(skmHandler);
    
            // Optionally disable parent handlers if needed
            LOGGER.setUseParentHandlers(false);
            sessionKeyManagerLogger.setUseParentHandlers(false);
        }
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

                ClusterId clusterId = new ClusterId("test.cluster");

                // Cluster membership mapping
                ClusterMembership membership = new ClusterMembership(Map.of(
                                node1, new NetworkAddress.InetAddress("127.0.0.1", port1),
                                node2, new NetworkAddress.InetAddress("127.0.0.1", port2)));

                NodeClientSecret nodeClientSecret1 = new NodeClientSecret(
                                clusterId,
                                node1,
                                "blahblah",
                                SRPUtils.generateSalt());

                final var v1 = SRPUtils.generateVerifier(constants, nodeClientSecret1.srpIdenity(),
                                nodeClientSecret1.password(), nodeClientSecret1.salt());

                final var nv1 = new NodeVerifier(nodeClientSecret1.srpIdenity(), v1.toString(16));

                LOGGER.info("Verifier v1: " + nv1 + " for node " + nodeClientSecret1.srpIdenity() + " with password "
                                + nodeClientSecret1.password());

                NodeClientSecret nodeClientSecret2 = new NodeClientSecret(
                                clusterId,
                                node2,
                                "moreblahblah",
                                SRPUtils.generateSalt());

                final var v2 = SRPUtils.generateVerifier(constants, nodeClientSecret2.srpIdenity(),
                                nodeClientSecret2.password(), nodeClientSecret2.salt());

                final var nv2 = new NodeVerifier(nodeClientSecret2.srpIdenity(), v2.toString(16));

                LOGGER.info("Verifier v2: " + nv2 + " for node " + nodeClientSecret2.srpIdenity() + " with password "
                                + nodeClientSecret2.password());

                Supplier<Map<NodeId, NodeVerifier>> verifierLookup = () -> Map.of(
                                node1, nv1,
                                node2, nv2);

                SessionKeyManager keyManager1 = new SessionKeyManager(node1, constants, nodeClientSecret1,
                                verifierLookup);
                SessionKeyManager keyManager2 = new SessionKeyManager(node2, constants, nodeClientSecret2,
                                verifierLookup);

                // Initialize networks with Supplier<ClusterMembership>
                network1 = new PaxeNetwork(keyManager1, port1, node1, () -> membership);
                network2 = new PaxeNetwork(keyManager2, port2, node2, () -> membership);

                network1.start();
                network2.start();
        }

        @AfterEach
        public void teardown() throws Exception {
                if (network1 != null)
                        network1.close();
                if (network2 != null)
                        network2.close();
        }

        @Test
        void testStartup() throws Exception {

                // Allow time for handshake to complete
                Thread.sleep(100);

                // Verify session keys were exchanged
                assertTrue(network1.keyManager.sessionKeys.containsKey(new NodeId((byte) 2)));
                assertTrue(network2.keyManager.sessionKeys.containsKey(new NodeId((byte) 1)));

                final var key1 = network1.keyManager.sessionKeys.get(new NodeId((byte) 2));
                final var key2 = network2.keyManager.sessionKeys.get(new NodeId((byte) 1));
                assertArrayEquals(key1, key2);
        }

        @Test
        void testHandshake() throws Exception {

                // Verify initial state
                var node1 = new NodeId((byte) 1);
                var node2 = new NodeId((byte) 2);

                assertFalse(network1.keyManager.sessionKeys.containsKey(node2));
                assertFalse(network2.keyManager.sessionKeys.containsKey(node1));

                // Wait for handshake
                int attempts = 0;
                while (attempts++ < 10) {
                        if (network1.keyManager.sessionKeys.containsKey(node2) &&
                                        network2.keyManager.sessionKeys.containsKey(node1)) {
                                break;
                        }
                        Thread.sleep(100);
                }

                assertTrue(network1.keyManager.sessionKeys.containsKey(node2),
                                "Node 1 missing session key");
                assertTrue(network2.keyManager.sessionKeys.containsKey(node1),
                                "Node 2 missing session key");
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
                                "Hello from Node 1".getBytes());

                PaxeMessage messageFromNode2 = new PaxeMessage(
                                new NodeId((byte) 2),
                                new NodeId((byte) 1),
                                channel,
                                "Hello from Node 2".getBytes());

                // Encrypt and send messages
                network1.encryptAndSend(messageFromNode1);
                network2.encryptAndSend(messageFromNode2);

                // Receive messages
                PaxeMessage receivedByNode2 = network2.receive(channel);
                PaxeMessage receivedByNode1 = network1.receive(channel);

                // Verify received messages
                assertArrayEquals(messageFromNode1.payload(), receivedByNode2.payload());
                assertArrayEquals(messageFromNode2.payload(), receivedByNode1.payload());
        }
}
