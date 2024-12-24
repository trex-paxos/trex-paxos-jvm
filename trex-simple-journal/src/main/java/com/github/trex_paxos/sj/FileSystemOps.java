package com.github.trex_paxos.sj;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

interface FileSystemOps {
  FileChannel openChannel(Path path, StandardOpenOption... options) throws IOException;

  Stream<Path> list(Path dir) throws IOException;

  void createDirectories(Path dir) throws IOException;

  boolean exists(Path path);

  void deleteIfExists(Path path) throws IOException;
}
