# Trex2: Paxos Algorithm for JVM

## Project Description
Trex2 is a Java library implementing the Paxos consensus algorithm for distributed systems. It provides strong consistency for cluster replication on the JVM, based on Leslie Lamport's "Paxos Made Simple" paper with optional Flexible Paxos support. The library prioritizes safety over availability, preferring to mark nodes as crashed rather than risk safety violations.

## File Structure Overview
```
trex-paxos-jvm/
├── trex-lib/           # Core Paxos algorithm implementation
├── trex-paxe/          # Optional encrypted UDP network protocol (PAXE)
├── trex-locks/         # Distributed locking (currently disabled)
├── papers/             # Academic papers referenced in implementation
├── pom.xml             # Maven multi-module configuration
└── README.md           # Detailed algorithm documentation
```

## Running Tests and Commands

### Build and Test
**Use `mvnd` (Maven Daemon) for faster builds if available:**
```bash
# Check for mvnd and set alias
alias mvn='command -v mvnd >/dev/null 2>&1 && mvnd || mvn'

# Build all modules
mvn clean compile

# Run all tests (includes property-based tests)
mvn test

# Run tests for specific module
mvn test -pl trex-lib

# Full verification (recommended before push)
mvn verify
```

### mvnd Installation
If `mvnd` is not installed:
```bash
# macOS
brew install mvnd

# Linux
sdk install mvnd

# Or download from https://github.com/apache/maven-mvnd
```

### Key Test Categories
- **Property Tests**: JQwik-based exhaustive testing of algorithm invariants
- **Simulation Tests**: 1,000+ randomized network partition scenarios
- **Unit Tests**: Core functionality validation

## Getting Started

### Prerequisites
- Java 24+ (with preview features enabled)
- Maven 3.6+

### Basic Usage
1. Implement the `Journal` interface for persistence
2. Configure cluster membership with unique node IDs
3. Choose transport layer (your own messaging or PAXE UDP protocol)
4. Implement application callback for chosen commands

## Coding Standards

### Core Principles
- **Data-Oriented Programming**: Use Records for immutable data, separate data from behavior
- **Functional Style**: Static methods operating on Records, Stream API instead of loops
- **Package Design**: Package-by-feature, default package-private scope, minimal public APIs
- **Modern Java**: JEP 467 Markdown docs, sealed interfaces, pattern matching, virtual threads

### Key Patterns
- Records + static methods = functional programming in Java
- Stream operations replace traditional for/while loops
- Switch expressions with exhaustive pattern matching
- Local classes/interfaces (JEP 371) for cohesive single-file modules
- Immutable data structures with pure functions

### Documentation
- Use `///` JEP 467 Markdown documentation comments
- No legacy `/** */` JavaDoc style
- Document behavior in utility classes, not data records

### Example Style
```java
/// Returns filtered results using stream operations
public static List<Result> filterValid(Stream<Record> records) {
    return records.filter(r -> r.isValid())
                  .map(r -> new Result(r.id(), r.value()))
                  .toList();
}
```

## Module-Specific Documentation
- **trex-lib/AGENTS.md**: Core Paxos algorithm implementation details
- **trex-paxe/AGENTS.md**: PAXE encrypted UDP network protocol specification

For detailed algorithm explanation and implementation notes, see the comprehensive README.md.

## Development Guidelines

### Commit Requirements
- **No marketing content**: Commits must be factual and technical
- **User name attribution**: All commits must use your actual name (not company/brand names)
- **SPDX headers**: All new Java files must include the copyright notice from `banner.txt`
- **Pre-commit hooks**: Run `setup-hooks.sh` to configure git hooks for SPDX header validation

### Copyright Notice
All new Java files must include the following SPDX header at the top:
```java
// SPDX-FileCopyrightText: 2024 - 2025 [Your Name]
// SPDX-License-Identifier: Apache-2.0
```

### File Linking (agents.md compatibility)
This repository uses the agents.md format for documentation. The `.openhands/microagents/repo.md` file is symlinked to the main `AGENTS.md` for forward compatibility with upcoming OpenHands agents.md support.

### Setup Development Environment
```bash
# Configure git hooks for SPDX header validation
./setup-hooks.sh

# Verify build before committing
mvn verify
```

End.
