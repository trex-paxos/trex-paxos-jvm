package com.github.trex_paxos;

public interface AcceptHandler {

    default PaxosData handleAccept(PaxosData data, Accept accept, Journal journal, Messaging io) {
        if ( lowerAccept(data, accept) || higherAcceptForCommittedSlot(accept.id(), data)) {
            io.nack(accept);
            return data;
        } else if (equalOrHigherAccept(data, accept)){
            return handleEqualOrHigherAccept(data, accept, journal, io);
        } else {
            throw new IllegalArgumentException(STR."\{accept.getClass().getCanonicalName()}:\{accept.toString()}");
        }
    }

    private static boolean equalOrHigherAccept(PaxosData data, Accept accept) {
        return data.progress().highestPromised().lessThanOrEqualTo(accept.id().number());
    }

    private static boolean higherAcceptForCommittedSlot(Identifier accept, PaxosData data) {
        return accept.number().greaterThan(data.progress().highestPromised()) &&
                accept.logIndex() <= data.progress().highestCommitted().logIndex();
    }

    private static Boolean lowerAccept(PaxosData data, Accept accept) {
        return accept.id().number().lessThan(data.progress().highestPromised());
    }

    default PaxosData handleEqualOrHigherAccept(PaxosData data, Accept accept, Journal io, Messaging messaging) {
        if (data.progress().highestPromised().greaterThan(accept.id().number())) {
            throw new IllegalArgumentException("Accept number must be greater or equal to the agent's promise.");
        }
        io.journalAccept(accept);
        PaxosData updatedData;
        if (higherAccept(data, accept)) {
            updatedData = data.withProgress(data.progress().withHighestPromised(accept.id().number()));
            io.journalProgress(updatedData.progress());
        } else {
            updatedData = data;
        }
        messaging.ack(accept);
        return updatedData;
    }

    private static Boolean higherAccept(PaxosData data, Accept accept) {
        return accept.id().number().greaterThan(data.progress().highestPromised());
    }
}
