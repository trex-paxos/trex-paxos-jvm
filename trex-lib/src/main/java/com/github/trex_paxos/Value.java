/*
 * Copyright 2024 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos;

import java.util.UUID;

public record Value(short from, UUID uuid, byte[] bytes) implements Message {
    public Value {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid cannot be null");
        }
        if (bytes == null) {
            throw new IllegalArgumentException("bytes cannot be null");
        }
        if( bytes.length > 1472 ) {
            // https://notes.shichao.io/tcpv1/ch10/
            throw new IllegalArgumentException("bytes cannot be longer than 1472 bytes");
        }
    }
}
