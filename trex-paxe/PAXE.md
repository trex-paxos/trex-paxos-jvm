
# PAXE Protocol Specification v0.9

## Overview
PAXE is a lightweight encrypted protocol for Paxos clusters supporting multiplexed channels over UDP. 
It provides 0-RTT resumption for established sessions and 1-RTT authentication for new sessions.

## Packet Format
```
[UDP Header]
[PAXE Header - 4 bytes]
  From Node ID:    2 byte
  To Node ID:      2 byte  
  Channel ID:      2 byte
[Encryption Header - 28 bytes] 
  Nonce:           12 bytes 
  Auth Tag:        16 bytes
[Encrypted Payload]
  Type:            1 byte
  Length:          4 bytes
  Data:            variable
```

## Channels
- Channel 0: Reserved for PAXE consensus messages

## Session Keys
- Derived from SRP exchange between node pairs
- Key = HKDF(srp_shared_secret, "PAXE-V2", from_id | to_id)
- Same key used for both directions between node pair

## Message Flow
1. SRP authentication (1-RTT) if no session exists:
   - Indicated by Auth Required flag
   - Must complete before encrypted traffic
2. Encrypted stream communication (0-RTT) using channels
3. Automatic session resumption after network changes
