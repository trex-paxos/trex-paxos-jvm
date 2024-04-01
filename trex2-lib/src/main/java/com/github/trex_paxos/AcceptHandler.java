package com.github.trex_paxos;

final class AcceptHandler {

    static boolean equalOrHigherAccept(Progress progress, Accept accept) {
        return progress.highestPromised().lessThanOrEqualTo(accept.id().number());
    }

    static boolean higherAcceptForCommittedSlot(Identifier accept, Progress progress) {
        return accept.number().greaterThan(progress.highestPromised()) &&
                accept.logIndex() <= progress.highestCommitted().logIndex();
    }

    static Boolean lowerAccept(Progress progress, Accept accept) {
        return accept.id().number().lessThan(progress.highestPromised());
    }

    static Boolean higherAccept(Progress progress, Accept accept) {
        return accept.id().number().greaterThan(progress.highestPromised());
    }
}
