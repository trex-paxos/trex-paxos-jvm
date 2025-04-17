// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.msg;

import java.util.List;
/// CatchupResponse is a message sent by the leader to a replica in response to a Catchup message.
/// It will only return the accept messages for fixed slots. We do not attempt to send any information
/// about promises as we do not want to change our own promise outside the normal prepare/accept flow.
/// @param from                 see {@link TrexMessage}
/// @param to                   see {@link DirectMessage}
/// @param accepts              the list of fixed accepts above the slot index requested.
public record CatchupResponse(short from,
                                short to,
                              List<Accept> accepts
) implements TrexMessage, DirectMessage, LearningMessage {
}
