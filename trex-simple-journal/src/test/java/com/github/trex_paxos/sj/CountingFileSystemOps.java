package com.github.trex_paxos.sj;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class CountingFileSystemOps implements FileSystemOps {
  private final FileSystemOps delegate;
  private final AtomicInteger count = new AtomicInteger(0);

  public CountingFileSystemOps(FileSystemOps delegate) {
    this.delegate = delegate;
  }

  private void increment() {
    count.incrementAndGet();
  }

  public int getCount() {
    return count.get();
  }

  @Override
  public FileChannel openChannel(Path path, StandardOpenOption... options) throws IOException {
    increment();
    return delegate.openChannel(path, options);
  }

  @Override
  public Stream<Path> list(Path dir) throws IOException {
    increment();
    return delegate.list(dir);
  }

  @Override
  public void createDirectories(Path dir) throws IOException {
    increment();
    delegate.createDirectories(dir);
  }

  @Override
  public boolean exists(Path path) {
    increment();
    return delegate.exists(path);
  }

  @Override
  public void deleteIfExists(Path path) throws IOException {
    increment();
    delegate.deleteIfExists(path);
  }
}
