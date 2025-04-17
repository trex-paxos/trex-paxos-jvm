// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

public record ClusterId(String id) {
    public ClusterId {
        if(id == null) {
            throw new IllegalArgumentException("id required");
        }
    }
}
