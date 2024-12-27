package com.github.trex_paxos.advisory_locks.udp;

import com.github.trex_paxos.advisory_locks.ClusterMembership;
import com.github.trex_paxos.advisory_locks.LockHandle;
import com.github.trex_paxos.advisory_locks.NodeId;
import com.github.trex_paxos.advisory_locks.TrexLockService;
import com.github.trex_paxos.advisory_locks.server.LockServerCommandValue;
import com.github.trex_paxos.advisory_locks.server.LockServerPickle;
import com.github.trex_paxos.advisory_locks.server.LockServerReturnValue;
import com.github.trex_paxos.advisory_locks.store.LockStore;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UdpLockServer implements TrexLockService, AutoCloseable {
  private static final Logger LOGGER = Logger.getLogger(UdpLockServer.class.getName());
  public static final int ETHERNET_MTU = 1400;
  private final NodeId nodeId;
  private final ClusterMembership membership;
  private final DatagramSocket socket;
  private final TrexLockService localService;
  private final Thread listenerThread;

  public UdpLockServer(NodeId nodeId, ClusterMembership membership,
                       TrexLockService localService, Duration socketTimeout) throws IOException {
    this.nodeId = nodeId;
    this.membership = membership;
    this.localService = localService;
    this.socket = new DatagramSocket(membership.nodePorts().get(nodeId));
    this.socket.setSoTimeout((int) socketTimeout.toMillis());
    // Start listener thread for incoming requests
    this.listenerThread = Thread.startVirtualThread(this::mainSocketReadLoop);
    LOGGER.info("Started UDP server for " + nodeId + " on port " + membership.nodePorts().get(nodeId));
  }

  private void mainSocketReadLoop() {
    while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
      try {
        byte[] buffer = new byte[ETHERNET_MTU];
        DatagramPacket request = new DatagramPacket(buffer, buffer.length);

        LOGGER.finer(() -> nodeId + " waiting for incoming request");
        socket.receive(request);
        LOGGER.finer(() -> nodeId + " received request from port " + request.getPort());

        final LockServerCommandValue command =
            LockServerPickle.unpickleCommand(Arrays.copyOf(request.getData(), request.getLength()));
        LOGGER.finer(() -> nodeId + " deserialized command: " + command);

        // Handle the command using local service
        @SuppressWarnings("SwitchStatementWithTooFewBranches")
        var result = switch (command) {
          case LockServerCommandValue.TryAcquireLock cmd -> {
            LOGGER.finer(() -> nodeId + " processing TryAcquireLock command");
            final Optional<LockHandle> handle = localService.tryLock(cmd.lockId(), cmd.expiryTime());
            Optional<LockStore.LockEntry> h2 = handle.map(h ->
                LockStore.createLockEntryFromHandle(h, cmd.expiryTime(), Duration.ofSeconds(1L)));
            yield new LockServerReturnValue.TryAcquireLockReturn(
                h2
            );
          }
          default -> throw new IllegalArgumentException("Unknown command: " + command);
        };
        LOGGER.finer(() -> nodeId + " executed command, result: " + result);
        // Send response
        byte[] response = LockServerPickle.pickle(result);

        LOGGER.finer(() -> nodeId + " serialized response length: " + response.length);
        DatagramPacket responsePacket = new DatagramPacket(
            response,
            response.length,
            request.getAddress(),
            request.getPort()
        );
        socket.send(responsePacket);
      } catch (IOException e) {
        if (!socket.isClosed()) {
          LOGGER.log(Level.WARNING, () -> String.format("%s error in listener thread. Error: %s, Cause: %s, Stack: %s",
              nodeId,
              e,
              e.getCause() != null ? e.getCause().toString() : "none",
              java.util.Arrays.toString(e.getStackTrace())
          ));
        }
      } catch (Exception e) {
        LOGGER.severe(() -> nodeId + " error listener work");
        throw new RuntimeException(e);
      }
    }
    LOGGER.info(() -> nodeId + " listener thread exiting");
  }

  @Override
  public void close() {
    listenerThread.interrupt();
    socket.close();
  }

  /// This is the main method that will be called by the client that will do the broadcast to all the nodes in the cluster.
  @Override
  public Optional<LockHandle> tryLock(String id, Instant expiryTime) {
    LOGGER.fine(() -> nodeId + " attempting tryLock for id=" + id);
    final var responses = new ArrayList<LockHandle>();
    membership.otherNodes(nodeId).forEach(targetId -> {
      try {
        byte[] data = LockServerPickle.pickle(new LockServerCommandValue.TryAcquireLock(id, expiryTime));
        DatagramPacket packet = new DatagramPacket(data, data.length,
            InetAddress.getLocalHost(),
            membership.nodePorts().get(targetId));
        LOGGER.finer(() -> nodeId + " sending request to " + targetId);
        socket.send(packet);
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, nodeId + " error communicating with " + targetId, e);
      }
    });
    do {
      byte[] buffer = new byte[ETHERNET_MTU];
      DatagramPacket response = new DatagramPacket(buffer, buffer.length);
      try {
        socket.receive(response);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      LockServerReturnValue ret = LockServerPickle
          .unpickleReturn(Arrays.copyOf(response.getData(), response.getLength()));
      LOGGER.finer(() -> nodeId + " received response");
      final var handle = LockStore.createHandleFromReturnValue(ret);
      responses.add(handle);
    }
    while (responses.size() < 2);
    final var result = responses.getFirst();
    LOGGER.info(() -> nodeId + " got quorum, returning handle: " + result);
    return Optional.of(result);
  }

  @Override
  public long expireTimeUnsafe(LockHandle lock) {
    return localService.expireTimeUnsafe(lock);
  }

  @Override
  public Instant expireTimeWithSafetyGap(LockHandle lock, Duration safetyGap) {
    return localService.expireTimeWithSafetyGap(lock, safetyGap);
  }

  @Override
  public boolean releaseLock(LockHandle lock) {
    return localService.releaseLock(lock);
  }
}

