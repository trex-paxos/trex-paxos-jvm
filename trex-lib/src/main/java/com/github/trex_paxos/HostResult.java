package com.github.trex_paxos;

import java.util.UUID;

public record HostResult<R>(long slot, UUID uuid, R result) {
}
