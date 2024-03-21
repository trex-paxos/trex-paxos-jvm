package com.github.trex_paxos;

import java.util.function.Function;

public interface AcceptHandler {

    default PaxosData handleAccept(PaxosIO io, PaxosAgent agent, Accept accept) {
        Function<Identifier, Boolean> lowerAccept =
                id -> id.number().
                        lessThan(
                                agent.data().progress().highestPromised());
        Function<Identifier, Boolean> higherAcceptForCommittedSlot =
                id -> id.number().greaterThan(
                            agent.data().progress().highestPromised())
                        &&
                        id.logIndex() <= agent.data().progress().highestCommitted().logIndex();

        if ((lowerAccept.apply(accept.id())) || (higherAcceptForCommittedSlot.apply(accept.id()))) {
            io.nack(accept);
            return agent.data();
        } else if (agent.data().progress().highestPromised(). lessThanOrEqualTo ( accept.id().number()) ){
            return handleHighAccept(io, agent.data(), accept);
        } else {
            throw new IllegalArgumentException(STR."\{accept.getClass().getCanonicalName()}:\{accept.toString()}");
        }
    }

    default PaxosData handleHighAccept(PaxosIO io, PaxosData data, Accept accept) {
        if (data.progress().highestPromised().greaterThan(accept.id().number())) {
            throw new IllegalArgumentException("Accept number must be greater or equal to the agent's promise.");
        }
        io.journal().accept(accept);
        PaxosData updatedData;
        if (accept.id().number().
                greaterThan(
                        data.progress().highestPromised())) {
            updatedData = highestPromisedSet(data, accept.id().number());
            io.journal().accept(updatedData.progress());
        } else {
            updatedData = data;
        }
        io.ack(accept);
        return updatedData;
    }

    default PaxosData highestPromisedSet(PaxosData data, BallotNumber number){
        throw new AssertionError("Not implemented");
    };
}
