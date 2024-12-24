package com.github.trex_paxos.sj;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class ThrowingFileSystemOps implements FileSystemOps {
  private final FileSystemOps delegate;
  private final AtomicInteger callCount = new AtomicInteger(0);
  private final int throwOnCall;

  public ThrowingFileSystemOps(FileSystemOps delegate, int throwOnCall) {
    this.delegate = delegate;
    this.throwOnCall = throwOnCall;
  }

  private void maybeThrow(String operation) throws IOException {
    int count = callCount.incrementAndGet();
    if (count == throwOnCall) {
      throw new IOException("Simulated failure during " + operation + " on call " + count);
    }
  }

  @Override
  public FileChannel openChannel(Path path, StandardOpenOption... options) throws IOException {
    maybeThrow("openChannel");
    return delegate.openChannel(path, options);
  }

  @Override
  public Stream<Path> list(Path dir) throws IOException {
    return Stream.empty();
  }

  @Override
  public void createDirectories(Path dir) throws IOException {

  }

  @Override
  public boolean exists(Path path) {
    return false;
  }

  @Override
  public void deleteIfExists(Path path) throws IOException {

  }

  // other methods similarly implemented
}
