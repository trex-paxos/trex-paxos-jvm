# Paxe Protocol Documentation

## Overview

Paxe implements authenticated encryption for Trex Paxos messages using AES-GCM. It supports both standard and Data
Encryption Key (DEK) modes for efficient large payload handling.

## Wire Format

### Message Header (8 bytes)

```
+--------+--------+--------+--------+--------+--------+--------+--------+
| fromId | toId   | channel| length                                    |
+--------+--------+--------+--------+--------+--------+--------+--------+
```

- fromId (2 bytes): Source node identifier
- toId (2 bytes): Destination node identifier
- channel (2 bytes): Communication channel identifier
- length (2 bytes): Payload length

### Standard Message Format

```
+----------------+--------+-----------+------------+-----------------+
| Header (8)     | Flags  | Nonce(12) | Payload    | Auth Tag (16)  |
+----------------+--------+-----------+------------+-----------------+
```

### DEK Message Format

```
+----------------+--------+-----------+------------+----------+--------+------------+-----------------+
| Header (8)     | Flags  | Nonce(12) | DEK Key(32)| DEKNonce | Length | Payload    | Auth Tag (16)  |
+----------------+--------+-----------+------------+----------+--------+------------+-----------------+
```

### Flags Byte Structure

- Bit 0: DEK flag (0=standard, 1=DEK mode)
- Bit 1: Must be 0
- Bit 2: Must be 1
- Bits 3-7: Reserved

## Key Classes

### PaxeNetwork

Core networking implementation handling:

- Message encryption/decryption
- Network I/O
- Channel management
- Pending message buffering

### SessionKeyManager

Manages secure key exchange:

- Implements SRP (RFC 5054) handshakes
- Tracks active sessions
- Handles key derivation
- Maintains node verifiers

### Crypto

Encryption primitives:

- AES-GCM operations
- Nonce generation
- Buffer management
- DEK encryption logic

### NetworkTestHarness

Test infrastructure providing:

- Network simulation
- Node creation
- Key exchange verification
- Cluster membership management

## Security Properties

### Authentication

- Every packet includes GCM authentication tag
- All headers are authenticated (fromId, toId, channel)
- Failed authentication triggers SecurityException

### Confidentiality

- AES-256-GCM for all payloads
- Unique nonce per message
- DEK mode for large messages

### Key Exchange

- SRP v6a (RFC 5054) for initial authentication
- Session key derivation via HKDF
- Key confirmation through GCM tag validation

## Usage Guidelines

### Message Size

- Standard mode for small messages < 64 byte (cpu cache line)
- DEK mode automatically used for larger payloads
- Maximum UDP packet size: 65507 bytes

### Channel Management

- System channels (1-99) reserved
- Application channels start from 100
- Broadcast vs direct messaging support

### Key Lifecycle

- Session keys rotated on network partition
- Verifiers distributed via configuration
- Node IDs must be globally unique

## Error Handling

### Network Errors

- Failed sends queued for retry
- Messages rejected if no session key
- Automatic key re-establishment

### Crypto Failures

- SecurityException on auth failures
- Buffer overflow protection
- Connection teardown on key errors

## Test Support

- InMemoryNetwork for unit testing
- NetworkTestHarness for integration
- Logging levels follow JUL conventions
