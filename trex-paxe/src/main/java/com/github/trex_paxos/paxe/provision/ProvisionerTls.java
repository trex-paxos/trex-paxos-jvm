// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe.provision;

import org.bouncycastle.tls.*;
import org.bouncycastle.tls.crypto.TlsSecret;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.bouncycastle.util.Strings;

import java.io.IOException;
import java.util.Vector;

/// Shared TLS 1.3 settings for the cluster PSK provisioning control plane.
final class ProvisionerTls {

  private static final int[] CIPHER_SUITES = {CipherSuite.TLS_AES_128_GCM_SHA256};

  private ProvisionerTls() {
  }

  static BcTlsCrypto crypto() {
    return new BcTlsCrypto();
  }

  static AbstractTlsClient client(BootstrapPsk bootstrapPsk, short[] pskKeyExchangeModes) {
    return new AbstractTlsClient(crypto()) {
      @Override
      protected int[] getSupportedCipherSuites() {
        return TlsUtils.getSupportedCipherSuites(getCrypto(), CIPHER_SUITES);
      }

      @Override
      protected ProtocolVersion[] getSupportedVersions() {
        return ProtocolVersion.TLSv13.only();
      }

      @Override
      public short[] getPskKeyExchangeModes() {
        return pskKeyExchangeModes;
      }

      @Override
      public Vector getExternalPSKs() {
        TlsSecret key = getCrypto().createSecret(bootstrapPsk.key());
        return TlsUtils.vectorOfOne(new BasicTlsPSKExternal(
            Strings.toUTF8ByteArray(bootstrapPsk.identity()),
            key,
            PRFAlgorithm.tls13_hkdf_sha256));
      }

      @Override
      public TlsAuthentication getAuthentication() throws IOException {
        throw new TlsFatalAlert(AlertDescription.internal_error);
      }
    };
  }

  static AbstractTlsServer server(BootstrapPsk bootstrapPsk, short[] pskKeyExchangeModes) {
    return new AbstractTlsServer(crypto()) {
      @Override
      public TlsCredentials getCredentials() {
        return null;
      }

      @Override
      protected int[] getSupportedCipherSuites() {
        return TlsUtils.getSupportedCipherSuites(getCrypto(), CIPHER_SUITES);
      }

      @Override
      protected ProtocolVersion[] getSupportedVersions() {
        return ProtocolVersion.TLSv13.only();
      }

      @Override
      public short[] getPskKeyExchangeModes() {
        return pskKeyExchangeModes;
      }

      @Override
      public TlsPSKExternal getExternalPSK(Vector identities) {
        byte[] expectedIdentity = Strings.toUTF8ByteArray(bootstrapPsk.identity());
        PskIdentity matchIdentity = new PskIdentity(expectedIdentity, 0L);

        for (int i = 0, count = identities.size(); i < count; ++i) {
          if (matchIdentity.equals(identities.elementAt(i))) {
            TlsSecret key = getCrypto().createSecret(bootstrapPsk.key());
            return new BasicTlsPSKExternal(expectedIdentity, key, PRFAlgorithm.tls13_hkdf_sha256);
          }
        }
        return null;
      }
    };
  }
}
