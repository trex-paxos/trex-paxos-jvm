package com.github.trex_paxos;

import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.TrexNetwork;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class InMemoryNetwork implements TrexNetwork {
  // Existing InMemoryNetwork implementation remains unchanged
  private final List<StackClusterImpl.ChannelAndSubscriber> handlers = new ArrayList<>();
  private final LinkedBlockingQueue<NetworkMessage> messageQueue = new LinkedBlockingQueue<>();
  private volatile boolean running = true;
  private final String networkId;

  public InMemoryNetwork(String networkId) {
    this.networkId = networkId;
    StackClusterImpl.LOGGER.fine(() -> "Created InMemoryNetwork: " + networkId);
  }

  private record NetworkMessage(short nodeId, Channel channel, ByteBuffer data) {
  }

  @Override
  public void send(Channel channel, short nodeId, ByteBuffer data) {
    if (running) {
      messageQueue.add(new NetworkMessage(nodeId, channel, data));
    }
  }

  @Override
  public void subscribe(Channel channel, NamedSubscriber handler) {
    StackClusterImpl.ChannelAndSubscriber channelAndSubscriber = new StackClusterImpl.ChannelAndSubscriber(channel, handler);
    handlers.add(channelAndSubscriber);
  }

  @Override
  public void start() {
    Thread.ofVirtual().name("network-" + networkId).start(() -> {
      while (running) {
        try {
          NetworkMessage msg = messageQueue.poll(100, TimeUnit.MILLISECONDS);

          if (msg != null) {
            handlers.forEach(h -> {
              if (h.channel().equals(msg.channel)) {
                StackClusterImpl.LOGGER.fine(() -> networkId + " received message on channel " + msg.channel + " from " + msg.nodeId + " delivering to " + h.subscriber().name());
                h.subscriber().accept(msg.data);
              }
            });
          }
        } catch (InterruptedException e) {
          if (running) {
            StackClusterImpl.LOGGER.warning(networkId + " message processor interrupted: " + e.getMessage());
          }
        }
      }
    });
    StackClusterImpl.LOGGER.info(() -> networkId + " network started with subscribers " + handlers);
  }

  @Override
  public void close() {
    StackClusterImpl.LOGGER.fine(() -> networkId + " network stopping");
    running = false;
  }
}
