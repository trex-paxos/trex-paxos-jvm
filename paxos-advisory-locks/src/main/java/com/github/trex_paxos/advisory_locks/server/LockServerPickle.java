package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.SerDe;
import com.github.trex_paxos.advisory_locks.store.LockStore;

import java.io.*;
import java.time.Instant;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;

public class LockServerPickle {

  private static final Logger LOGGER = java.util.logging.Logger.getLogger(LockServerPickle.class.getName());

  public static byte[] pickle(LockServerCommandValue value) {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(baos)) {

      switch (value) {
        case LockServerCommandValue.TryAcquireLock cmd -> {
          dos.writeByte(0);
          final var lockId = cmd.lockId();
          dos.writeUTF(lockId);
          final var expiryTime = cmd.expiryTime().toEpochMilli();
          dos.writeLong(expiryTime);
        }
        case LockServerCommandValue.ReleaseLock cmd -> {
          dos.writeByte(1);
          dos.writeUTF(cmd.lockId());
          dos.writeLong(cmd.stamp());
        }
        case LockServerCommandValue.GetLock cmd -> {
          dos.writeByte(2);
          dos.writeUTF(cmd.lockId());
        }
      }
      dos.flush();
      return baos.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static LockServerCommandValue unpickleCommand(byte[] bytes) {
    LOGGER.finer(() -> "Unpickling command: " + bytes.length);
    try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
         DataInputStream dis = new DataInputStream(bais)) {

      return switch (dis.readByte()) {
        case 0 -> new LockServerCommandValue.TryAcquireLock(
            dis.readUTF(),
            Instant.ofEpochMilli(dis.readLong())
        );
        case 1 -> new LockServerCommandValue.ReleaseLock(
            dis.readUTF(),
            dis.readLong()
        );
        case 2 -> new LockServerCommandValue.GetLock(
            dis.readUTF()
        );
        default -> throw new IOException("Unknown command type");
      };
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Exception unpicking command: " + e.getMessage(), e);
      throw new UncheckedIOException(e);
    }
  }

  public static byte[] pickle(LockServerReturnValue value) {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(baos)) {

      switch (value) {
        case LockServerReturnValue.TryAcquireLockReturn ret -> {
          dos.writeByte(0);
          writeOptionalLockEntry(dos, ret.result());
        }
        case LockServerReturnValue.ReleaseLockReturn ret -> {
          dos.writeByte(1);
          dos.writeBoolean(ret.result());
        }
        case LockServerReturnValue.GetLockReturn ret -> {
          dos.writeByte(2);
          writeOptionalLockEntry(dos, ret.result());
        }
      }
      dos.flush();
      final var bytes = baos.toByteArray();
      LOGGER.finer(() -> {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        long crcValue = crc32.getValue();
        return "LockServerReturnValue pickle length: " + bytes.length +
            " crc32: " + crcValue;
      });
      return bytes;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static LockServerReturnValue unpickleReturn(byte[] bytes) {
    LOGGER.finer(() -> {
      CRC32 crc32 = new CRC32();
      crc32.update(bytes);
      long crcValue = crc32.getValue();
      return "LockServerReturnValue unpickle length: " + bytes.length +
          " crc32: " + crcValue;
    });
    try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
         DataInputStream dis = new DataInputStream(bais)) {

      return switch (dis.readByte()) {
        case 0 -> new LockServerReturnValue.TryAcquireLockReturn(
            readOptionalLockEntry(dis)
        );
        case 1 -> new LockServerReturnValue.ReleaseLockReturn(
            dis.readBoolean()
        );
        case 2 -> new LockServerReturnValue.GetLockReturn(
            readOptionalLockEntry(dis)
        );
        default -> throw new IOException("Unknown return type");
      };
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private static void writeOptionalLockEntry(DataOutputStream dos, Optional<LockStore.LockEntry> entry) throws IOException {
    dos.writeBoolean(entry.isPresent());
    if (entry.isPresent()) {
      dos.writeUTF(entry.get().lockId());
      dos.writeLong(entry.get().stamp());
      // Write seconds and nanos separately for Instant
      dos.writeLong(entry.get().expiryTime().getEpochSecond());
      dos.writeInt(entry.get().expiryTime().getNano());
      dos.writeLong(entry.get().acquiredTimeMillis());
    }
  }

  private static Optional<LockStore.LockEntry> readOptionalLockEntry(DataInputStream dis) throws IOException {
    if (dis.readBoolean()) {
      String lockId = dis.readUTF();
      long stamp = dis.readLong();
      // Read seconds and nanos separately for Instant
      long seconds = dis.readLong();
      int nanos = dis.readInt();
      long acquiredTimeMillis = dis.readLong();
      return Optional.of(new LockStore.LockEntry(
          lockId,
          stamp,
          Instant.ofEpochSecond(seconds, nanos),
          acquiredTimeMillis
      ));
    }
    return Optional.empty();
  }

  static SerDe<LockServerCommandValue> serdeCmd = new SerDe<>() {
    @Override
    public byte[] serialize(LockServerCommandValue value) {
      return LockServerPickle.pickle(value);
    }

    @Override
    public LockServerCommandValue deserialize(byte[] bytes) {
      return LockServerPickle.unpickleCommand(bytes);
    }
  };

  static SerDe<LockServerReturnValue> serdeResult = new SerDe<>() {
    @Override
    public byte[] serialize(LockServerReturnValue value) {
      return LockServerPickle.pickle(value);
    }

    @Override
    public LockServerReturnValue deserialize(byte[] bytes) {
      return LockServerPickle.unpickleReturn(bytes);
    }
  };
}
