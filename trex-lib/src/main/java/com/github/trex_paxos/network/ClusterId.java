package com.github.trex_paxos.network;

public record ClusterId(String id) {
    public ClusterId {
        if(id == null) {
            throw new IllegalArgumentException("id required");
        }
    }
}
