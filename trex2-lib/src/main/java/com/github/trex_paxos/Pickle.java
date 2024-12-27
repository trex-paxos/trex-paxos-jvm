/*
 * Copyright 2024 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos;

import java.nio.ByteBuffer;

/// Pickle is a utility class for serializing and deserializing the record types that the [Journal] uses.
/// Java serialization is famously broken but the Java Platform team are working on it.
/// This class does things the boilerplate way.
public class Pickle {
    
    private static final int BALLOT_NUMBER_SIZE = Integer.BYTES + 1; // counter (4 bytes) + nodeIdentifier (1 byte)
    private static final int PROGRESS_SIZE = 1 + BALLOT_NUMBER_SIZE + Long.BYTES; // nodeIdentifier (1 byte) + BallotNumber + highestFixedIndex (8 bytes)

    public static byte[] writeProgress(Progress progress) {
        ByteBuffer buffer = ByteBuffer.allocate(PROGRESS_SIZE);
        write(progress, buffer);
        return buffer.array();
    }

    public static void write(Progress progress, ByteBuffer buffer) {
        buffer.put(progress.nodeIdentifier());
        write(progress.highestPromised(), buffer);
        buffer.putLong(progress.highestFixedIndex());
    }

    public static Progress readProgress(byte[] pickled) {
        ByteBuffer buffer = ByteBuffer.wrap(pickled);
        return readProgress(buffer);
    }

    private static Progress readProgress(ByteBuffer buffer) {
        byte nodeId = buffer.get();
        BallotNumber ballotNumber = readBallotNumber(buffer);
        long highestFixedIndex = buffer.getLong();
        return new Progress(nodeId, ballotNumber, highestFixedIndex);
    }

    public static void write(BallotNumber n, ByteBuffer buffer) {
        buffer.putInt(n.counter());
        buffer.put(n.nodeIdentifier());
    }

    public static BallotNumber readBallotNumber(ByteBuffer buffer) {
        return new BallotNumber(buffer.getInt(), buffer.get());
    }
}