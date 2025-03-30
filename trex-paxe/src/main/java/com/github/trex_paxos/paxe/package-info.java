/// # PAXE Wire Protocol Implementation
///
/// The PAXE (Paxos Encrypted) protocol is designed for atomic broadcast consensus, featuring authenticated encryption
/// with AES-GCM-256, strict message structure validation, and channel-based message routing.
///
/// ## Protocol Overview
///
/// Key features include:
/// - **Authenticated Encryption**: AES-GCM-256 ensures secure data transmission.
/// - **Message Validation**: Strict validation ensures protocol compliance.
/// - **Channel Routing**: Messages are routed through designated channels.
///
/// ## Protocol Structure
///
/// Each message consists of:
/// 1. **Header (8 bytes)**: Contains source/destination node IDs, channel identifier, and payload length.
/// 2. **Flags (1 byte)**: Indicates encryption mode and protocol version.
/// 3. **Nonce (12 bytes)**: Used for AES-GCM encryption.
/// 4. **Encrypted Payload**: Includes a 16-byte authentication tag.
///
/// ## Encryption Modes
///
/// PAXE will negotiate a peer-to-peer session key between each node pair using RFC5054 SRP6a to generate a shared secret.
///
/// Supports two operational modes:
/// - **Small Messages**: When the packet size is small enough to be in a cache line the entire message is encrypted with each node pair session key.
/// - **DEK Encryption**: Large messages are encrypted with a Data Encryption Key (DEK) that is shared among all messages then the DEK is encrypted with each node pair session key.
///
/// @see com.github.trex_paxos.paxe.SRPUtils SRPUtils for session key negotiation using RFC5054 SRP6a
/// @see javax.crypto.Cipher
package com.github.trex_paxos.paxe;
