package com.github.trex_paxos.sj;

import com.github.trex_paxos.BallotNumber;
import com.github.trex_paxos.Command;
import com.github.trex_paxos.msg.Accept;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class FileJournalTest {
  private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(FileJournalTest.class.getName());
  private Path tempDir;
  private int totalOperationCount;

  @BeforeEach
  void setup() throws IOException {
    tempDir = Files.createTempDirectory("journal-test");
  }

  @Test
  void testRecoveryFromIOFailures() throws IOException {
    // First run with real FS to count operations
    FileSystemOps realFs = new RealFileSystemOps();
    CountingFileSystemOps countingFs = new CountingFileSystemOps(realFs);

    try (FileJournal journal = FileJournal.initializeNewNode(tempDir, (byte) 1, countingFs)) {
      journal.writeAccept(new Accept((byte) 1, 1, BallotNumber.MIN, new Command("test", "data".getBytes())));
      journal.sync();
    }

    totalOperationCount = countingFs.getCount();

    // Now test throwing at each operation
    for (int i = 1; i <= totalOperationCount; i++) {
      LOGGER.info("Testing failure at operation " + i);

      FileSystemOps throwingFs = new ThrowingFileSystemOps(new RealFileSystemOps(), i);

      try {
        try (FileJournal journal = FileJournal.initializeNewNode(tempDir, (byte) 1, throwingFs)) {
          journal.writeAccept(new Accept((byte) 1, 1, BallotNumber.MIN, new Command("test", "data".getBytes())));
          journal.sync();
        }
        fail("Should have thrown on operation " + i);
      } catch (UncheckedIOException expected) {
        // Expected
      }

      // Verify recovery works
      try (FileJournal journal = FileJournal.openExisting(tempDir, (byte) 1, new RealFileSystemOps())) {
        Optional<Accept> accept = journal.readAccept(1);
        assertTrue(accept.isEmpty() || accept.get().slot() == 1);
      }
    }
  }
}
