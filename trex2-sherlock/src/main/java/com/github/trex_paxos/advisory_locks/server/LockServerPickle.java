package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.advisory_locks.store.LockStore;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LockServerPickle {

  private static final Logger LOGGER = java.util.logging.Logger.getLogger(LockServerPickle.class.getName());
  public static byte[] pickle(LockServerCommandValue value) {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(baos)) {

      switch(value) {
        case LockServerCommandValue.TryAcquireLock cmd -> {
          dos.writeByte(0);
          dos.writeUTF(cmd.lockId());
          dos.writeLong(cmd.holdDuration().toMillis());
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
    try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
         DataInputStream dis = new DataInputStream(bais)) {

      return switch(dis.readByte()) {
        case 0 -> new LockServerCommandValue.TryAcquireLock(
            dis.readUTF(),
            Duration.ofMillis(dis.readLong())
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
      LOGGER.log(Level.SEVERE, "Exception unpickling command: " + e.getMessage(), e);
      throw new UncheckedIOException(e);
    }
  }

  public static byte[] pickle(LockServerReturnValue value) {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(baos)) {

      switch(value) {
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
      return baos.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static LockServerReturnValue unpickleReturn(byte[] bytes) {
    try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
         DataInputStream dis = new DataInputStream(bais)) {

      return switch(dis.readByte()) {
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
}
