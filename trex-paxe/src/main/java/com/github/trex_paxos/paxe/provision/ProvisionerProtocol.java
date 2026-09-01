// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe.provision;

import com.github.trex_paxos.paxe.ClusterKeyManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/// Application messages exchanged over the TLS 1.3 provisioning channel (never on PAXE UDP).
final class ProvisionerProtocol {

  static final byte[] REQUEST_MAGIC = {'T', 'P', 'K', '1'};
  static final byte[] RESPONSE_OK_MAGIC = {'T', 'P', 'K', '0'};
  static final byte[] RESPONSE_FAIL_MAGIC = {'T', 'P', 'K', 'F'};

  static final int REQUEST_SIZE = REQUEST_MAGIC.length + 1;
  static final int RESPONSE_OK_SIZE = RESPONSE_OK_MAGIC.length + ClusterKeyManager.CLUSTER_PSK_SIZE;
  static final int RESPONSE_FAIL_SIZE = RESPONSE_FAIL_MAGIC.length + 1;

  private ProvisionerProtocol() {
  }

  static byte[] request(byte epoch) {
    var message = new byte[REQUEST_SIZE];
    System.arraycopy(REQUEST_MAGIC, 0, message, 0, REQUEST_MAGIC.length);
    message[REQUEST_MAGIC.length] = epoch;
    return message;
  }

  static byte parseRequestEpoch(byte[] request) {
    if (request.length != REQUEST_SIZE || !Arrays.equals(REQUEST_MAGIC, 0, REQUEST_MAGIC.length, request, 0, REQUEST_MAGIC.length)) {
      throw new SecurityException("Invalid provisioning request");
    }
    return request[REQUEST_MAGIC.length];
  }

  static byte[] success(byte[] clusterPsk) {
    if (clusterPsk.length != ClusterKeyManager.CLUSTER_PSK_SIZE) {
      throw new IllegalArgumentException("Cluster PSK must be " + ClusterKeyManager.CLUSTER_PSK_SIZE + " bytes");
    }
    var message = new byte[RESPONSE_OK_SIZE];
    System.arraycopy(RESPONSE_OK_MAGIC, 0, message, 0, RESPONSE_OK_MAGIC.length);
    System.arraycopy(clusterPsk, 0, message, RESPONSE_OK_MAGIC.length, clusterPsk.length);
    return message;
  }

  static byte[] failure(byte reason) {
    return new byte[]{RESPONSE_FAIL_MAGIC[0], RESPONSE_FAIL_MAGIC[1], RESPONSE_FAIL_MAGIC[2], RESPONSE_FAIL_MAGIC[3], reason};
  }

  static byte[] readClusterPskResponse(InputStream input) throws IOException {
    var magic = readExact(input, REQUEST_MAGIC.length);
    if (Arrays.equals(RESPONSE_OK_MAGIC, magic)) {
      return readExact(input, ClusterKeyManager.CLUSTER_PSK_SIZE);
    }
    if (Arrays.equals(RESPONSE_FAIL_MAGIC, 0, RESPONSE_FAIL_MAGIC.length, magic, 0, RESPONSE_FAIL_MAGIC.length)) {
      var reason = readExact(input, 1);
      throw new SecurityException("Provisioning failed with reason " + (reason[0] & 0xFF));
    }
    throw new SecurityException("Invalid provisioning response");
  }

  static void writeAll(OutputStream output, byte[] message) throws IOException {
    output.write(message);
    output.flush();
  }

  static byte[] readExact(InputStream input, int length) throws IOException {
    var buffer = new byte[length];
    var offset = 0;
    while (offset < length) {
      int read = input.read(buffer, offset, length - offset);
      if (read < 0) {
        throw new IOException("Provisioning stream closed before message completed");
      }
      offset += read;
    }
    return buffer;
  }
}
