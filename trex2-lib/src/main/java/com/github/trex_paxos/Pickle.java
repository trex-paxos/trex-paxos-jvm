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
package com.github.trex_paxos;

import java.io.*;

/// Pickle is a utility class for serializing and deserializing the record types that the [Journal] uses.
/// Java serialization is famously broken but the Java Platform team are working on it.
/// This class does things the boilerplate way.
public class Pickle {

  public static byte[] writeProgress(Progress progress) throws IOException {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(byteArrayOutputStream)) {
      write(progress, dos);
      return byteArrayOutputStream.toByteArray();
    }
  }

  public static void write(Progress progress, DataOutputStream dos) throws IOException {
    dos.writeByte(progress.nodeIdentifier());
    write(progress.highestPromised(), dos);
    dos.writeLong(progress.highestFixedIndex());
  }

  public static Progress readProgress(byte[] pickled) throws IOException {
    try (ByteArrayInputStream bis = new ByteArrayInputStream(pickled);
         DataInputStream dis = new DataInputStream(bis)) {
      return readProgress(dis);
    }
  }

  private static Progress readProgress(DataInputStream dis) throws IOException {
    return new Progress(dis.readByte(), readBallotNumber(dis), dis.readLong());
  }

  public static void write(BallotNumber n, DataOutputStream dataOutputStream) throws IOException {
    dataOutputStream.writeInt(n.counter());
    dataOutputStream.writeByte(n.nodeIdentifier());
  }

  public static BallotNumber readBallotNumber(DataInputStream dataInputStream) throws IOException {
    return new BallotNumber(dataInputStream.readInt(), dataInputStream.readByte());
  }
}
