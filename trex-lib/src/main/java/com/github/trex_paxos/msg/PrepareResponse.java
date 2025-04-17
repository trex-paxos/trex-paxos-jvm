// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.msg;

import com.github.trex_paxos.SlotTerm;

import java.util.Objects;
import java.util.Optional;

/// A PrepareResponse is a response to a {@link Prepare} message. It contains the vote and the highest unfixed log entry if any.
/// When the vote is positive then we have made a promise to not accept any future Prepare or Accept messages with a lower ballot number.
/// We must only use the information about promises to pick a value we must not change a promise outside the normal prepare/accept flow.
/// @param from                 see {@link TrexMessage}
/// @param to                   see {@link DirectMessage}
/// @param vote                 whether wre have voted for or voted against the Prepare message based on our past promises. .
/// @param journaledAccept      the highest unfixed log entry if any.
/// @param highestAcceptedIndex additional information about the highest accepted index so that a leader can learn of more slots that it needs to recover.
public record PrepareResponse(
    short from,
    short to,
    short era,
    Vote vote,
    Optional<Accept> journaledAccept,
    long highestAcceptedIndex
) implements TrexMessage, DirectMessage {
  public PrepareResponse{
    Objects.requireNonNull(vote);
    Objects.requireNonNull(journaledAccept);
    journaledAccept.ifPresent(Objects::requireNonNull);
    if( vote.from() != from) {
      throw new IllegalArgumentException("Vote from must be the same as the from field");
    }
    if( vote.to() != to) {
      throw new IllegalArgumentException("Vote to must be the same as the to field");
    }
  }
  public record Vote(
      // spookily intellij says there are no usages of this field, but if I remove it everything breaks
      short from,
      // spookily intellij says there are no usages of this field, but if I remove it everything breaks
      short to,
      SlotTerm slotTerm,
      boolean vote
  ) {
  }
}
