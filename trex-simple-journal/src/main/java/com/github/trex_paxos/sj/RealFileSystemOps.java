package com.github.trex_paxos.sj;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

class RealFileSystemOps implements FileSystemOps {
  @Override
  public FileChannel openChannel(Path path, StandardOpenOption... options) throws IOException {
    return FileChannel.open(path, options);
  }

  @Override
  public Stream<Path> list(Path dir) throws IOException {
    return Files.list(dir);
  }

  @Override
  public void createDirectories(Path dir) throws IOException {
    Files.createDirectories(dir);
  }

  @Override
  public boolean exists(Path path) {
    return Files.exists(path);
  }

  @Override
  public void deleteIfExists(Path path) throws IOException {
    Files.deleteIfExists(path);
  }
}
