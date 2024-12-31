package com.github.trex_paxos.paxe;

import java.util.Objects;

public record SessionKeyPair(
    NodeId from,
    NodeId to
) {
    public SessionKeyPair {
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");
        // Normalize order for key lookup
        if (from.compareTo(to) > 0) {
            var temp = from;
            from = to;
            to = temp;
        }
    }
}
