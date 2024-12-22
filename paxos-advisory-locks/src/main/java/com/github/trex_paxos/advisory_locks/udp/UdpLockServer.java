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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("preview")
public class UdpLockServer implements TrexLockService, AutoCloseable {
  private static final Logger LOGGER = Logger.getLogger(UdpLockServer.class.getName());
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
    this.listenerThread = Thread.startVirtualThread(this::listen);
    LOGGER.info("Started UDP server for " + nodeId + " on port " + membership.nodePorts().get(nodeId));
  }

  private void listen() {
    while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
      try {
        byte[] buffer = new byte[8192];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        LOGGER.finer(() -> nodeId + " waiting for incoming request");
        socket.receive(packet);
        LOGGER.finer(() -> nodeId + " received request from port " + packet.getPort());

        final LockServerCommandValue command = LockServerPickle.unpickleCommand(packet.getData());
        LOGGER.finer(() -> nodeId + " deserialized command: " + command);

        // Handle the command using local service
        @SuppressWarnings("SwitchStatementWithTooFewBranches")
        var result = switch (command) {
          case LockServerCommandValue.TryAcquireLock cmd -> {
            LOGGER.finer(() -> nodeId + " processing TryAcquireLock command");
            final Optional<LockHandle> handle = localService.tryLock(cmd.lockId(), cmd.holdDuration());
            Optional<LockStore.LockEntry> h2 = handle.map(h ->
                LockStore.createLockEntryFromHandle(h, cmd.holdDuration(), Duration.ofSeconds(1L)));
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
            response, response.length,
            packet.getAddress(), packet.getPort()
        );
        socket.send(responsePacket);
        LOGGER.finer(() -> nodeId + " sent response to port " + packet.getPort());

      } catch (IOException e) {
        if (!socket.isClosed()) {
          //
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
    LOGGER.fine(() -> nodeId + " listener thread exiting");
  }

  @Override
  public void close() {
    listenerThread.interrupt();
    socket.close();
  }

  @Override
  public Optional<LockHandle> tryLock(String id, Duration holdDuration) {
    LOGGER.fine(() -> nodeId + " attempting tryLock for id=" + id);
    try (var scope = new StructuredTaskScope<LockHandle>()) {
      final var responses = new ConcurrentHashMap<NodeId, LockHandle>();

      membership.otherNodes(nodeId).forEach(targetId ->
          scope.fork(() -> {
            try {
              LOGGER.finer(() -> nodeId + " preparing request to " + targetId);
              byte[] data = LockServerPickle.pickle(new LockServerCommandValue.TryAcquireLock(id, holdDuration));
              DatagramPacket packet = new DatagramPacket(data, data.length,
                  InetAddress.getLocalHost(),
                  membership.nodePorts().get(targetId));

              LOGGER.finer(() -> nodeId + " sending request to " + targetId);
              socket.send(packet);

              byte[] buffer = new byte[8192];
              DatagramPacket response = new DatagramPacket(buffer, buffer.length);
              LOGGER.finer(() -> nodeId + " waiting for response from " + targetId);
              socket.receive(response);
              LOGGER.finer(() -> nodeId + " received response from " + targetId);

              LockServerReturnValue ret = LockServerPickle.unpickleReturn(response.getData());
              LockHandle handle = LockStore.createHandleFromReturnValue(ret);
              responses.put(targetId, handle);
              LOGGER.finer(() -> nodeId + " processed response from " + targetId + " total responses=" + responses.size());

              if (responses.size() == 2) {
                LOGGER.fine(() -> nodeId + " got quorum, shutting down scope");
                scope.shutdown();
              }
              return handle;
            } catch (Exception e) {
              LOGGER.log(Level.WARNING, nodeId + " error communicating with " + targetId, e);
              return null;
            }
          }));

      LOGGER.fine(() -> nodeId + " joining scope");
      scope.join();

      if (responses.size() < 2) {
        LOGGER.warning(() -> nodeId + " failed to achieve quorum - needed 2 remote responses but got " + responses.size());
        throw new RuntimeException("Failed to achieve quorum - needed 2 remote responses but got " + responses.size());
      }

      LOGGER.fine(() -> nodeId + " succeeded with " + responses.size() + " responses");
      return Optional.of(responses.values().iterator().next());

    } catch (InterruptedException e) {
      LOGGER.log(Level.WARNING, nodeId + " interrupted during tryLock", e);
      throw new RuntimeException(e);
    }
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

