// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe.provision;

import com.github.trex_paxos.paxe.ClusterKeyManager;
import org.bouncycastle.tls.PskKeyExchangeMode;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsFatalAlertReceived;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/// TLS 1.3 client that fetches a cluster PSK for a target epoch using a bootstrap external PSK.
public final class ClusterPskProvisionerClient {

  public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = (int) TimeUnit.SECONDS.toMillis(5);

  private ClusterPskProvisionerClient() {
  }

  /// Fetches the cluster PSK without mutating `keyManager`. Use {@link #provisionAndInstall} to install.
  public static byte[] fetchClusterPsk(BootstrapPsk bootstrapPsk, InetSocketAddress server, byte epoch)
      throws IOException {
    return fetchClusterPsk(bootstrapPsk, server, epoch, DEFAULT_CONNECT_TIMEOUT_MILLIS);
  }

  public static byte[] fetchClusterPsk(BootstrapPsk bootstrapPsk, InetSocketAddress server, byte epoch,
                                       int connectTimeoutMillis) throws IOException {
    Objects.requireNonNull(bootstrapPsk, "bootstrapPsk");
    Objects.requireNonNull(server, "server");

    try (Socket socket = new Socket()) {
      socket.connect(server, connectTimeoutMillis);
      var protocol = new TlsClientProtocol(socket.getInputStream(), socket.getOutputStream());
      protocol.connect(ProvisionerTls.client(bootstrapPsk, new short[]{PskKeyExchangeMode.psk_dhe_ke}));

      ProvisionerProtocol.writeAll(protocol.getOutputStream(), ProvisionerProtocol.request(epoch));
      return ProvisionerProtocol.readClusterPskResponse(protocol.getInputStream());
    } catch (TlsFatalAlertReceived e) {
      throw new SecurityException("TLS provisioning handshake failed", e);
    }
  }

  /// Installs the fetched cluster PSK into `keyManager` only after a successful TLS exchange.
  public static void provisionAndInstall(BootstrapPsk bootstrapPsk, ClusterKeyManager keyManager,
                                         InetSocketAddress server, byte epoch) throws IOException {
    Objects.requireNonNull(keyManager, "keyManager");
    byte[] clusterPsk = fetchClusterPsk(bootstrapPsk, server, epoch);
    keyManager.installEpoch(epoch, clusterPsk);
  }
}
