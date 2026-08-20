# Requirements Document

## Introduction

This specification defines the requirements for refactoring the judge pipeline to support territory exchanges between NPCs and players in both directions. Currently, the judge pipeline only handles player-to-player territory exchanges through the `Results` data structure. This enhancement will enable NPCs to capture territories from players and vice versa, creating more dynamic gameplay where NPCs can be active participants in territorial control.

## Glossary

- **Judge_Pipeline**: The existing LLM-driven system that evaluates player actions and determines outcomes including territory gains/losses
- **Territory_Exchange**: The process of transferring ownership of a territory from one entity to another
- **NPC**: Non-Player Character that can own and control territories
- **Player**: Human-controlled character that can own and control territories
- **WorldManager**: The global state management system that tracks territory ownership and applies changes
- **Results**: The data structure returned by the judge pipeline containing gains and losses
- **Territory**: A map location that can be owned by players or NPCs

## Requirements

### Requirement 1: NPC Territory Ownership Support

**User Story:** As a game system, I want NPCs to be able to own and control territories, so that they can participate in territorial gameplay mechanics.

#### Acceptance Criteria

1. WHEN an NPC gains a territory, THE System SHALL update the territory's ruler field to the NPC's name
2. WHEN an NPC loses a territory, THE System SHALL remove the NPC's ownership and update the territory accordingly
3. WHEN territory ownership changes involve NPCs, THE System SHALL record appropriate ActionHistory events
4. THE System SHALL maintain consistency between NPC.capturedTerritory list and Territory.ruler field for NPC-owned territories

### Requirement 2: Player-to-NPC Territory Transfer

**User Story:** As a player, I want my actions to potentially result in NPCs gaining my territories, so that NPCs can respond dynamically to my gameplay choices.

#### Acceptance Criteria

1. WHEN the judge pipeline determines a player loses territory to an NPC, THE System SHALL transfer ownership from player to NPC
2. WHEN a territory is transferred from player to NPC, THE System SHALL update the NPC's capturedTerritory list
3. WHEN a territory is transferred from player to NPC, THE System SHALL remove the territory from player's capturedTerritory list
4. WHEN a player-to-NPC transfer occurs, THE System SHALL log a TerritoryEventMetadata with correct previousOwner and newOwner fields

### Requirement 3: NPC-to-Player Territory Transfer

**User Story:** As a player, I want to be able to capture territories from NPCs through my actions, so that I can expand my territorial control by defeating or negotiating with NPCs.

#### Acceptance Criteria

1. WHEN the judge pipeline determines a player gains territory from an NPC, THE System SHALL transfer ownership from NPC to player
2. WHEN a territory is transferred from NPC to player, THE System SHALL update the player's capturedTerritory list
3. WHEN a territory is transferred from NPC to player, THE System SHALL remove the territory from NPC's capturedTerritory list
4. WHEN an NPC-to-player transfer occurs, THE System SHALL log a TerritoryEventMetadata with correct previousOwner and newOwner fields

### Requirement 4: Enhanced Results Data Structure

**User Story:** As a system architect, I want the Results data structure to support NPC involvement in territory exchanges, so that the judge pipeline can specify complex territorial outcomes.

#### Acceptance Criteria

1. WHEN the judge pipeline processes territory changes involving NPCs, THE Results SHALL include NPC names in territory gain/loss lists
2. WHEN territory exchanges involve NPCs, THE System SHALL parse NPC names from Results.territoryGained and Results.territoryLost
3. THE System SHALL distinguish between player names and NPC names when processing Results
4. WHEN Results contain NPC-related territory changes, THE System SHALL validate that referenced NPCs exist in world.npcs before processing
5. WHEN an NPC referenced in Results does not exist in world.npcs, THE System SHALL skip the territory exchange and log a warning

### Requirement 5: Territory Resolution and Validation

**User Story:** As a system administrator, I want territory exchanges to be validated and resolved correctly, so that the game state remains consistent and accurate.

#### Acceptance Criteria

1. WHEN processing territory exchanges, THE System SHALL validate that all referenced territories exist in world.mapTiles
2. WHEN processing territory exchanges involving NPCs, THE System SHALL validate that all referenced NPCs exist in world.npcs
3. WHEN a territory exchange references a non-existent NPC, THE System SHALL skip the exchange and log a warning that the NPC must be registered first
4. WHEN a territory exchange fails validation, THE System SHALL log appropriate warnings and skip the invalid exchange
5. THE System SHALL maintain referential integrity between Territory.ruler and owner's capturedTerritory list
6. WHEN an NPC gains territory but does not exist in world.npcs, THE System SHALL defer the territory grant until the NPC is properly registered

### Requirement 6: ActionHistory Integration

**User Story:** As a game developer, I want all NPC-related territory exchanges to be properly logged, so that the game history and scoring systems can track these events.

#### Acceptance Criteria

1. WHEN an NPC gains territory, THE System SHALL record a TERRITORY event with the NPC as the new owner
2. WHEN an NPC loses territory, THE System SHALL record a TERRITORY event with the NPC as the previous owner
3. WHEN territory exchanges involve NPCs, THE TerritoryEventMetadata SHALL correctly identify NPC names in previousOwner and newOwner fields
4. THE System SHALL ensure ActionHistory events for NPC territory exchanges are compatible with existing HistoryParser and ScoreManager systems

### Requirement 7: World State Synchronization

**User Story:** As a system architect, I want NPC territory ownership to be synchronized across all game systems, so that the UI, scoring, and gameplay systems have consistent data.

#### Acceptance Criteria

1. WHEN NPC territory ownership changes, THE System SHALL update both Territory.ruler and NPC.capturedTerritory atomically
2. WHEN territory exchanges occur, THE System SHALL use WorldManager.worldMutex to ensure thread-safe updates
3. THE System SHALL maintain consistency between world.mapTiles territory ownership and world.npcs territory lists
4. WHEN territory ownership changes, THE System SHALL ensure all references are updated before releasing the mutex
