// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe.provision;

import com.github.trex_paxos.paxe.ClusterKeyManager;
import org.bouncycastle.tls.PskKeyExchangeMode;
import org.bouncycastle.tls.TlsServerProtocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/// TLS 1.3 server that distributes the same cluster PSK to every member for a requested epoch.
public final class ClusterPskProvisionerServer implements AutoCloseable {

  private final BootstrapPsk bootstrapPsk;
  private final Function<Byte, byte[]> clusterPskForEpoch;
  private final ServerSocket serverSocket;
  private final ExecutorService connections;
  private final AtomicBoolean running = new AtomicBoolean(true);

  public ClusterPskProvisionerServer(BootstrapPsk bootstrapPsk, Function<Byte, byte[]> clusterPskForEpoch,
                                     InetSocketAddress bindAddress) throws IOException {
    this.bootstrapPsk = Objects.requireNonNull(bootstrapPsk, "bootstrapPsk");
    this.clusterPskForEpoch = Objects.requireNonNull(clusterPskForEpoch, "clusterPskForEpoch");
    this.serverSocket = new ServerSocket();
    this.serverSocket.bind(bindAddress);
    this.connections = Executors.newThreadPerTaskExecutor(
        Thread.ofPlatform().name("cluster-psk-provisioner-", 0).factory());
    Thread.ofPlatform()
        .name("cluster-psk-provisioner-accept")
        .start(this::acceptLoop);
  }

  public int port() {
    return serverSocket.getLocalPort();
  }

  private void acceptLoop() {
    while (running.get()) {
      try {
        Socket socket = serverSocket.accept();
        connections.submit(() -> handleConnection(socket));
      } catch (IOException e) {
        if (running.get()) {
          throw new RuntimeException("Provisioner accept loop failed", e);
        }
      }
    }
  }

  private void handleConnection(Socket socket) {
    try (socket) {
      var protocol = new TlsServerProtocol(socket.getInputStream(), socket.getOutputStream());
      protocol.accept(ProvisionerTls.server(bootstrapPsk, new short[]{PskKeyExchangeMode.psk_dhe_ke}));

      var request = ProvisionerProtocol.readExact(protocol.getInputStream(), ProvisionerProtocol.REQUEST_SIZE);
      byte epoch = ProvisionerProtocol.parseRequestEpoch(request);
      byte[] clusterPsk = clusterPskForEpoch.apply(epoch);
      if (clusterPsk == null || clusterPsk.length != ClusterKeyManager.CLUSTER_PSK_SIZE) {
        ProvisionerProtocol.writeAll(protocol.getOutputStream(), ProvisionerProtocol.failure((byte) 1));
        return;
      }
      ProvisionerProtocol.writeAll(protocol.getOutputStream(), ProvisionerProtocol.success(clusterPsk));
    } catch (Exception ignored) {
      // Failed provisioning must not mutate remote state; local handler just drops the connection.
    }
  }

  @Override
  public void close() throws IOException {
    running.set(false);
    serverSocket.close();
    connections.close();
  }
}
