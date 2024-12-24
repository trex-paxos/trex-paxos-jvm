package com.github.trex_paxos.sj;

import com.github.trex_paxos.*;
import com.github.trex_paxos.msg.Accept;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import java.util.zip.CRC32;

public class FileJournal implements Journal, AutoCloseable {
  private static final Logger LOGGER = Logger.getLogger(FileJournal.class.getName());
  public static final int MAX_FILE_SIZE = 512 * 1024; // 512KB aligned to SSD blocks
  private static final int HEADER_SIZE = 8; // 4 bytes size + 4 bytes CRC32

  private final Path directory;
  private final byte nodeIdentifier;
  private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
  private final FileSystemOps fs;

  // Track file metadata for truncation decisions
  record JournalFile(int fileNumber, long maxSlot) {
  }

  private final ConcurrentNavigableMap<Integer, JournalFile> fileMetadata = new ConcurrentSkipListMap<>();


  private FileJournal(Path directory, byte nodeIdentifier, FileSystemOps fs) {
    this.directory = directory;
    this.nodeIdentifier = nodeIdentifier;
    this.fs = fs;

    if (!fs.exists(directory)) {
      throw new IllegalArgumentException(String.format(
          "Directory %s does not exist. To initialize a new node use FileJournal.initializeNewNode()",
          directory));
    }

    try {
      recoverFileMetadata();
      if (slotIndex.isEmpty()) {
        throw new IllegalArgumentException(String.format(
            "No valid journal found in %s. To initialize a new node use FileJournal.initializeNewNode()",
            directory));
      }
      openCurrentFile();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to initialize journal", e);
    }
  }

  @Override
  public void close() throws IOException {
    if (currentChannel != null) {
      currentChannel.close();
    }
  }

  public static FileJournal initializeNewNode(Path directory, byte nodeIdentifier, FileSystemOps fs) {
    try {
      fs.createDirectories(directory);
      FileJournal journal = new FileJournal(directory, nodeIdentifier, fs);
      journal.writeAccept(new Accept(nodeIdentifier, 0, BallotNumber.MIN, NoOperation.NOOP));
      journal.writeProgress(new Progress(nodeIdentifier));
      LOGGER.info("Initialized new node in " + directory);
      return journal;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to initialize new node", e);
    }
  }

  public static FileJournal openExisting(Path directory, byte nodeIdentifier, FileSystemOps fs) {
    LOGGER.info("Opening existing node in " + directory);
    return new FileJournal(directory, nodeIdentifier, fs);
  }

  // Update all file operations to use fs instead of Files/FileChannel directly
  private void openCurrentFile() throws IOException {
    Path file = getPath(currentFileNumber.get());
    currentChannel = fs.openChannel(file,
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE);
    currentPosition.set(currentChannel.size());
    LOGGER.info("Opened journal file " + currentFileNumber.get() +
        " at position " + currentPosition.get());
  }

  record FilePosition(int fileNumber, long offset) {
  }

  private final ConcurrentNavigableMap<Long, FilePosition> slotIndex = new ConcurrentSkipListMap<>();

  private volatile FileChannel currentChannel;
  private final AtomicLong currentPosition = new AtomicLong(0);
  private final AtomicInteger currentFileNumber = new AtomicInteger(0);

  @SuppressWarnings("unused")
  public void truncate(long minClusterSlot) {
    lock.writeLock().lock();
    try {
      var obsoleteFiles = fileMetadata.values().stream()
          .filter(f -> f.maxSlot() < minClusterSlot)
          .toList();

      for (var file : obsoleteFiles) {
        fileMetadata.remove(file.fileNumber());
        slotIndex.entrySet().removeIf(e -> e.getValue().fileNumber() == file.fileNumber());
        Files.deleteIfExists(getPath(file.fileNumber()));
        LOGGER.info("Truncated journal file " + file.fileNumber() +
            " with max slot " + file.maxSlot());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to truncate journals", e);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void writeProgress(Progress progress) {
    if (progress.nodeIdentifier() != nodeIdentifier) {
      throw new IllegalArgumentException("Node identifier mismatch");
    }
    lock.writeLock().lock();
    try {
      LOGGER.fine(() -> "Writing progress: " + progress);
      byte[] bytes = Pickle.writeProgress(progress);
      appendRecord(RecordType.PROGRESS, bytes, 0);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write progress", e);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Progress readProgress(byte nodeId) {
    if (nodeId != nodeIdentifier) {
      throw new IllegalArgumentException("Node identifier mismatch");
    }
    lock.readLock().lock();
    try {
      return readLatestProgress();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read progress", e);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void writeAccept(Accept accept) {
    lock.writeLock().lock();
    try {
      LOGGER.fine(() -> "Writing accept at slot " + accept.slot());
      try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
           DataOutputStream dos = new DataOutputStream(baos)) {
        PicklePAXE.pickle(accept, dos);
        appendRecord(RecordType.ACCEPT, baos.toByteArray(), accept.slot());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write accept", e);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Optional<Accept> readAccept(long logIndex) {
    lock.readLock().lock();
    try {
      FilePosition pos = slotIndex.get(logIndex);
      if (pos == null) {
        return Optional.empty();
      }
      return readAcceptAtPosition(pos);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read accept", e);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void sync() {
    lock.writeLock().lock();
    try {
      LOGGER.fine("Syncing journal to disk");
      currentChannel.force(true);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to sync journal", e);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public long highestLogIndex() {
    return slotIndex.isEmpty() ? 0 : slotIndex.lastKey();
  }

  private void recoverFileMetadata() throws IOException {
    LOGGER.info("Recovering file metadata from " + directory);
    try (var files = Files.list(directory)) {
      files.filter(p -> p.toString().matches("journal-\\d+\\.dat"))
          .forEach(this::scanFile);
    }
  }

  private void scanFile(Path file) {
    try {
      int fileNum = extractFileNumber(file);
      long maxSlot = scanFileForMaxSlot(file);
      fileMetadata.put(fileNum, new JournalFile(fileNum, maxSlot));
      currentFileNumber.set(Math.max(currentFileNumber.get(), fileNum));
      LOGGER.info("Scanned journal file " + fileNum + " with max slot " + maxSlot);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to scan file: " + file, e);
    }
  }

  private int extractFileNumber(Path file) {
    String name = file.getFileName().toString();
    return Integer.parseInt(name.substring(8, name.length() - 4));
  }

  private long scanFileForMaxSlot(Path file) throws IOException {
    long maxSlot = 0;
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long position = 0;
      while (position < channel.size()) {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        if (channel.read(header, position) != HEADER_SIZE) {
          throw new IOException("Incomplete header read in file: " + file);
        }
        header.flip();
        int size = header.getInt();
        int storedCrc = header.getInt();

        ByteBuffer data = ByteBuffer.allocate(size);
        if (channel.read(data, position + HEADER_SIZE) != size) {
          throw new IOException("Incomplete data read in file: " + file);
        }
        data.flip();

        byte[] bytes = new byte[size];
        data.get(bytes);

        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        if (storedCrc != (int) crc32.getValue()) {
          LOGGER.warning("CRC mismatch in file: " + file);
          throw new IOException("CRC mismatch in file: " + file);
        }

        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
          Accept accept = (Accept) PicklePAXE.unpickle(dis);
          slotIndex.put(accept.slot(), new FilePosition(currentFileNumber.get(), position));
          maxSlot = Math.max(maxSlot, accept.slot());
        }

        position += HEADER_SIZE + size;
      }
    }
    return maxSlot;
  }

  private Progress readLatestProgress() throws IOException {
    // Read through current file backwards to find latest progress record
    // Implementation needed - for now just return initial progress
    LOGGER.fine("Reading latest progress record");
    return new Progress(nodeIdentifier);
  }

  private void appendRecord(RecordType type, byte[] data, long slot) throws IOException {
    if (currentPosition.get() + HEADER_SIZE + data.length > MAX_FILE_SIZE) {
      rotateFile(slot);
    }

    ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
    CRC32 crc32 = new CRC32();
    crc32.update(data);

    header.putInt(data.length);
    header.putInt((int) crc32.getValue());
    header.flip();

    int written = currentChannel.write(header, currentPosition.get());
    if (written != HEADER_SIZE) {
      throw new IOException("Incomplete header write");
    }

    written = currentChannel.write(ByteBuffer.wrap(data), currentPosition.get() + HEADER_SIZE);
    if (written != data.length) {
      throw new IOException("Incomplete data write");
    }

    slotIndex.put(slot, new FilePosition(currentFileNumber.get(), currentPosition.get()));
    currentPosition.addAndGet(HEADER_SIZE + data.length);

    LOGGER.fine(() -> String.format("Appended %s record at slot %d", type, slot));
  }

  private Optional<Accept> readAcceptAtPosition(FilePosition pos) throws IOException {
    try (FileChannel channel = FileChannel.open(
        getPath(pos.fileNumber()),
        StandardOpenOption.READ)) {

      ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
      if (channel.read(header, pos.offset()) != HEADER_SIZE) {
        throw new IOException("Incomplete header read");
      }
      header.flip();

      int size = header.getInt();
      int storedCrc = header.getInt();

      ByteBuffer data = ByteBuffer.allocate(size);
      if (channel.read(data, pos.offset() + HEADER_SIZE) != size) {
        throw new IOException("Incomplete data read");
      }
      data.flip();

      byte[] bytes = new byte[size];
      data.get(bytes);

      CRC32 crc32 = new CRC32();
      crc32.update(bytes);
      if (storedCrc != (int) crc32.getValue()) {
        LOGGER.warning("CRC mismatch reading accept");
        throw new IOException("CRC mismatch reading accept");
      }

      try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
        return Optional.of((Accept) PicklePAXE.unpickle(dis));
      }
    }
  }

  private Path getPath(int fileNumber) {
    return directory.resolve(String.format("journal-%d.dat", fileNumber));
  }

  private void rotateFile(long lastSlot) throws IOException {
    LOGGER.info("Rotating journal file " + currentFileNumber.get());
    currentChannel.force(true);
    currentChannel.close();

    fileMetadata.put(currentFileNumber.get(),
        new JournalFile(currentFileNumber.get(), lastSlot));

    currentFileNumber.incrementAndGet();
    openCurrentFile();
    currentPosition.set(0);
  }

  private enum RecordType {PROGRESS, ACCEPT}
  
}
