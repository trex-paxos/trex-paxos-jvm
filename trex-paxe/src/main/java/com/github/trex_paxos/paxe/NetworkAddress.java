package com.github.trex_paxos.paxe;

public sealed interface NetworkAddress {
    String hostString();
    int port();
    
    record InetAddress(String host, int port) implements NetworkAddress {
        public InetAddress {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid port: " + port);
            }
        }
        
        @Override public String hostString() { return host; }
    }
    
    record HostName(String hostname, int port) implements NetworkAddress {
        public HostName {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid port: " + port);
            }
        }
        
        @Override public String hostString() { return hostname; }
    }
}