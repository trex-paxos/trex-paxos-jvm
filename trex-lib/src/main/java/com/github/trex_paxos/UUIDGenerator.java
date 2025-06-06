// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/// The Java UUID library lets us create a UUID from two longs.
/// In the most significant long we put the time in milliseconds.
/// We then bit shift the time left by 20 bits and mask in a counter.
/// This gives us good time based ordering within a single JVM.
/// The ordering across servers will naturally be subject to clock drift between hosts.
/// For the last significant bits we use a pure random long to makes the UUIDs globally unique.
/// The RFC for time based UUIDs suggest that 10M UUIDs per second can be generated. On an M1 Mac the Java core Type 4
/// pure random UUID generation gives me about 0.6M per second. This class gets about 0.5M per second.
@SuppressWarnings("unused")
public class UUIDGenerator {
  /// A trick from the core UUID class is to use holder class to defer initialization until needed.
  private static class LazyRandom {
    static final SecureRandom RANDOM = new SecureRandom();
  }

  private static final AtomicLong sequence = new AtomicLong();

  /// This takes the Unix/Java epoch time in milliseconds, bit shifts it left by 20 bits, and then masks in the least
  /// significant 20 bits of the local counter. That gives us a million unique values per millisecond.
  static long epochTimeThenCounterMsb() {
    long currentMillis = System.currentTimeMillis();
    // Take the least significant 20 bits from our atomic sequence
    long counter20bits = sequence.incrementAndGet() & 0xFFFFF;
    return (currentMillis << 20) | counter20bits;
  }

  /// There is no guarantee that the time+counter of the most significant long will be unique across JVMs.
  /// In the lower 64 bits we use a random long. This makes it improbably to get any collisions across JVMs.
  /// Within a given JVM we will have good time based ordering.
  @SuppressWarnings("unused")
  public static UUID generateUUID() {
    // As the most significant bits use ms time then counter for sub-millisecond ordering.
    long msb = epochTimeThenCounterMsb();
    // As the least significant bits use a random long which will give us uniqueness across JVMs.
    long lsb = LazyRandom.RANDOM.nextLong();
    return new UUID(msb, lsb);
  }
}
