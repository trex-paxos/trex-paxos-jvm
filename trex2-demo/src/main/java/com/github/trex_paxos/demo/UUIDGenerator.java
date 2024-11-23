/*
 * Copyright 2024 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos.demo;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/// The core java UUID generator class can can only create weak Type 3 md5 UUIDs and strong Type 4 random UUIDs.
/// While it can read Type 1 time based UUIDs it cannot create them. This class creates time ordered UUIDs that are
/// not official UUIDs but work for our purposes.
/// The Java UUID library lets us create a UUID two longs. In the most significant long we put the time in milliseconds
/// ito the higher bits and an atomic counter in the lowest bits.
/// This gives us good time based ordering within a single JVM.
/// For the last significant bits we use a pure random long. That effectively makes all of our UUIDs unique across
/// all JVMs.
/// The RFC for time based UUIDs suggest that 10M UUIDs per second can be generated. On an M1 Mac the Java core Type 4
/// pure random UUID generation gives me about 0.6M per second. This class gets about 0.5M per second.
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
  public static UUID generateUUID() {
    // As the most significant bits use ms time then counter for sub-millisecond ordering.
    long msb = epochTimeThenCounterMsb();
    // As the least significant bits use a random long which will give us uniqueness across JVMs.
    long lsb = LazyRandom.RANDOM.nextLong();
    return new UUID(msb, lsb);
  }
}
