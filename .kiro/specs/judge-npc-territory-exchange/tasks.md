# Implementation Plan: Judge Pipeline NPC Territory Exchange

## Overview

This implementation plan converts the feature design into a series of incremental coding tasks that will extend the existing judge pipeline to support territory exchanges between NPCs and players. Each task builds on previous work and focuses on specific components while maintaining backward compatibility with existing player-to-player territory exchanges.

## Tasks

- [ ] 1. Enhance judge pipeline context injection
  - Modify `buildJudge()` function to include NPC and territory context in pipeline
  - Update context injection to provide current NPC states and territory ownership
  - Ensure context is properly serialized and accessible to pipeline pipes
  - _Requirements: 4.2, 4.3, 4.4_

- [ ]* 1.1 Write property test for context injection
  - **Property 5: Entity Type Detection and Validation**
  - **Validates: Requirements 4.2, 4.3, 4.4**

- [ ] 2. Update gains and losses pipe prompts and context
  - Enhance system prompt to include NPC territory exchange awareness
  - Update context injection prompts to explain NPC and territory data
  - Modify footer prompt to clarify NPC name usage in Results
  - _Requirements: 4.1, 4.2_

- [ ]* 2.1 Write unit tests for enhanced prompts
  - Test prompt generation with NPC context
  - Verify context injection includes all required data
  - _Requirements: 4.1, 4.2_

- [ ] 3. Create territory exchange detection logic
  - Implement `detectTerritoryExchange()` function to analyze Results and story context
  - Add `extractNpcFromStoryContext()` helper to identify NPCs in story text
  - Create `TerritoryExchange` and `TerritoryExchangeContext` data classes
  - _Requirements: 2.1, 3.1, 4.2, 4.3_

- [ ]* 3.1 Write property test for territory exchange detection
  - **Property 2: Player-NPC Territory Transfer Completeness**
  - **Property 3: NPC-Player Territory Transfer Completeness**
  - **Validates: Requirements 2.1, 2.2, 2.3, 3.1, 3.2, 3.3**

- [ ] 4. Implement enhanced territory validation
  - Create `validateTerritoryTransferEntities()` function
  - Add `TerritoryTransferValidation` and `EntityInfo` data classes
  - Implement validation for NPCs, players, and territories
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ]* 4.1 Write property test for validation logic
  - **Property 6: Invalid Entity Handling**
  - **Validates: Requirements 4.5, 5.1, 5.3, 5.4**

- [ ] 5. Checkpoint - Ensure pipeline enhancements work
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 6. Enhance WorldManager territory processing
  - Modify `applyTerritoryChanges()` to use new territory exchange detection
  - Add NPC-aware territory transfer logic
  - Integrate validation before processing transfers
  - _Requirements: 1.1, 1.2, 2.1, 3.1_

- [ ]* 6.1 Write property test for WorldManager territory processing
  - **Property 1: NPC Territory Ownership Consistency**
  - **Validates: Requirements 1.1, 1.2, 1.4**

- [ ] 7. Implement NPC territory transfer operations
  - Create `applyNpcTerritoryTransfer()` function
  - Handle Player-to-NPC and NPC-to-Player transfers
  - Update both Territory.ruler and entity capturedTerritory lists atomically
  - _Requirements: 1.1, 1.2, 2.2, 2.3, 3.2, 3.3_

- [ ]* 7.1 Write property test for NPC territory transfers
  - **Property 2: Player-NPC Territory Transfer Completeness**
  - **Property 3: NPC-Player Territory Transfer Completeness**
  - **Validates: Requirements 2.1, 2.2, 2.3, 3.1, 3.2, 3.3**

- [ ] 8. Enhance ActionHistory logging for NPC exchanges
  - Update `logTerritoryEvent()` to handle NPC names in metadata
  - Ensure TerritoryEventMetadata correctly identifies NPC owners
  - Verify compatibility with existing ActionHistory systems
  - _Requirements: 1.3, 2.4, 3.4, 6.1, 6.2, 6.3_

- [ ]* 8.1 Write property test for ActionHistory logging
  - **Property 4: Territory Exchange ActionHistory Logging**
  - **Validates: Requirements 1.3, 2.4, 3.4, 6.1, 6.2, 6.3**

- [ ] 9. Add global consistency validation
  - Implement consistency checks between Territory.ruler and entity capturedTerritory lists
  - Add validation that runs after territory operations
  - Ensure referential integrity across all players and NPCs
  - _Requirements: 1.4, 5.5, 7.1, 7.3_

- [ ]* 9.1 Write property test for global consistency
  - **Property 7: Global Territory Ownership Consistency**
  - **Validates: Requirements 5.5, 7.1, 7.3**

- [ ] 10. Implement error handling and logging
  - Add comprehensive error logging for validation failures
  - Implement graceful handling of non-existent entities
  - Ensure invalid exchanges are skipped without affecting valid ones
  - _Requirements: 4.5, 5.3, 5.4_

- [ ]* 10.1 Write unit tests for error handling
  - Test non-existent NPC handling
  - Test non-existent territory handling
  - Test malformed Results processing
  - _Requirements: 4.5, 5.3, 5.4_

- [ ] 11. Integration testing with existing systems
  - Verify compatibility with HistoryParser.parseActionHistory()
  - Verify compatibility with ScoreManager.processActionHistory()
  - Test end-to-end judge pipeline execution with NPC exchanges
  - _Requirements: 6.4_

- [ ]* 11.1 Write property test for system compatibility
  - **Property 8: ActionHistory System Compatibility**
  - **Validates: Requirements 6.4**

- [ ] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests validate universal correctness properties
- Unit tests validate specific examples and edge cases
- Checkpoints ensure incremental validation
- All changes maintain backward compatibility with existing player-to-player exchanges
