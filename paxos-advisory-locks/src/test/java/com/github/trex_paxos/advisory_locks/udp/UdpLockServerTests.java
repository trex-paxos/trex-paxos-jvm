package com.github.trex_paxos.advisory_locks.udp;

import com.github.trex_paxos.advisory_locks.ClusterMembership;
import com.github.trex_paxos.advisory_locks.NodeId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;
import java.util.logging.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UdpLockServer Tests")
public class UdpLockServerTests {
  private static final Logger LOGGER = Logger.getLogger(UdpLockServerTests.class.getName());

  @BeforeAll
  static void setupLogging() {
    Logger rootLogger = Logger.getLogger("");
    rootLogger.setLevel(Level.FINER);

    // Remove existing handlers
    for (Handler handler : rootLogger.getHandlers()) {
      rootLogger.removeHandler(handler);
    }

    // Add console handler that writes to stdout
    ConsoleHandler handler = new ConsoleHandler() {{
      setOutputStream(System.out);
    }};
    handler.setLevel(Level.FINER);
    handler.setFormatter(new SimpleFormatter() {
      @Override
      public String format(LogRecord record) {
        return String.format("[%s] %s: %s%n",
            record.getLevel(),
            record.getLoggerName(),
            record.getMessage());
      }
    });
    rootLogger.addHandler(handler);
  }

  @Test
  void testFiveNodeClusterWrite() throws Exception {

    var membership = new ClusterMembership(Map.of(
        new NodeId((byte) 1), 9901,
        new NodeId((byte) 2), 9902,
        new NodeId((byte) 3), 9903,
        new NodeId((byte) 4), 9904,
        new NodeId((byte) 5), 9905
    ));

    var testService1 = new TestLockService();
    var testService2 = new TestLockService();
    var testService3 = new TestLockService();
    var testService4 = new TestLockService();
    var testService5 = new TestLockService();

    final var socketTimeout = Duration.ofSeconds(1);

    try (var scope = new StructuredTaskScope.ShutdownOnFailure();
         var server1 = new UdpLockServer(new NodeId((byte) 1), membership, testService1, socketTimeout);
         var _ = new UdpLockServer(new NodeId((byte) 2), membership, testService2, socketTimeout);
         var _ = new UdpLockServer(new NodeId((byte) 3), membership, testService3, socketTimeout);
         var _ = new UdpLockServer(new NodeId((byte) 4), membership, testService4, socketTimeout);
         var _ = new UdpLockServer(new NodeId((byte) 5), membership, testService5, socketTimeout)) {

      var deadline = Instant.now().plusSeconds(1);
      scope.joinUntil(deadline);

      var handle = server1.tryLock("test-lock", Duration.ofMinutes(1));

      assertThat(handle).isNotNull();
      assertThat(testService1.tryLockIds).hasSize(1);
      assertThat(testService2.tryLockIds).hasSize(1);
      assertThat(testService3.tryLockIds).hasSize(1);
      assertThat(testService4.tryLockIds).hasSize(1);
      assertThat(testService5.tryLockIds).hasSize(1);
    }
  }
}
