
# Trex-Lib: Core Paxos Algorithm

## Overview
Core implementation of the Paxos consensus algorithm with exhaustive property testing. Provides the fundamental building blocks for distributed consensus without network transport.

## Key Components

### Core Records
- **BallotNumber**: Unique proposal identifier `(counter, nodeId)`
- **Command**: Application command to replicate `(uuid, operationBytes)`
- **Accept**: Proposal message `(logIndex, ballotNumber, command)`
- **Prepare**: Leader election message `(logIndex, ballotNumber)`

### Algorithm Phases
1. **Steady State**: Leader streams accept messages, commits on majority
2. **Leader Election**: Prepare/promise exchange for slot recovery
3. **Learning**: Fixed messages notify nodes of committed values

### Safety Invariants
- Promises apply to both prepare and accept messages
- Values chosen by majority are immutable
- New leaders recover all previously attempted slots

### Testing Strategy
- JQwik property tests exhaustively check all branches
- 1,000+ randomized network partition simulations
- Runtime safety checks throughout algorithm execution

## Usage
Implement `Journal` interface for persistence, configure cluster membership, provide application callback for chosen commands.

## Package Structure
- `msg/`: Protocol message records
- Core algorithm classes with package-private scope
- Property tests in same package as implementation
