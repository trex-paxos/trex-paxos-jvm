// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// Holds cluster-wide pre-shared keys indexed by epoch for coordinated rotation.
/// Every cluster member uses the same 32-byte AES-256 key for a given epoch.
public final class ClusterKeyManager {

  public static final int CLUSTER_PSK_SIZE = 32;

  private final Map<Byte, byte[]> keysByEpoch = new ConcurrentHashMap<>();
  private volatile byte currentEpoch;

  public ClusterKeyManager(byte[] clusterPsk) {
    this(clusterPsk, (byte) 0);
  }

  public ClusterKeyManager(byte[] clusterPsk, byte epoch) {
    Objects.requireNonNull(clusterPsk, "clusterPsk");
    if (clusterPsk.length != CLUSTER_PSK_SIZE) {
      throw new IllegalArgumentException("Cluster PSK must be " + CLUSTER_PSK_SIZE + " bytes");
    }
    keysByEpoch.put(epoch, clusterPsk.clone());
    currentEpoch = epoch;
  }

  public byte currentEpoch() {
    return currentEpoch;
  }

  public void setCurrentEpoch(byte epoch) {
    if (!keysByEpoch.containsKey(epoch)) {
      throw new IllegalArgumentException("No cluster key installed for epoch " + (epoch & 0xFF));
    }
    currentEpoch = epoch;
  }

  /// Installs a key for the next epoch while the previous epoch remains valid during overlap.
  public void installEpoch(byte epoch, byte[] clusterPsk) {
    Objects.requireNonNull(clusterPsk, "clusterPsk");
    if (clusterPsk.length != CLUSTER_PSK_SIZE) {
      throw new IllegalArgumentException("Cluster PSK must be " + CLUSTER_PSK_SIZE + " bytes");
    }
    keysByEpoch.put(epoch, clusterPsk.clone());
  }

  public void retireEpoch(byte epoch) {
    keysByEpoch.remove(epoch);
  }

  public byte[] keyForEpoch(byte epoch) {
    byte[] key = keysByEpoch.get(epoch);
    if (key == null) {
      throw new SecurityException("No cluster key for epoch " + (epoch & 0xFF));
    }
    return key;
  }

  public boolean hasEpoch(byte epoch) {
    return keysByEpoch.containsKey(epoch);
  }

  @Override
  public String toString() {
    return "ClusterKeyManager{currentEpoch=" + (currentEpoch & 0xFF) + ", epochs=" + keysByEpoch.keySet() + "}";
  }

  /// Constant-time comparison is not required for epoch lookup; keys are erased on retire.
  public void clear() {
    keysByEpoch.values().forEach(key -> Arrays.fill(key, (byte) 0));
    keysByEpoch.clear();
  }
}
