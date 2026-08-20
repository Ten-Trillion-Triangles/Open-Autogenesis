package agent.builders.lorebook

import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.deserialize
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import gameState.WorldManager
import globals.BedrockConfig
import kotlinx.coroutines.sync.withLock
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.Player

fun mergeCharacterEntry(existing: CharacterEntry?, new: CharacterEntry): CharacterEntry {
    if (existing == null) return new
    return CharacterEntry(
        name = existing.name,
        description = if (new.description.isNotBlank()) "${existing.description}\n${new.description}" else existing.description,
        aliases = (existing.aliases + new.aliases).distinct(),
        affiliations = (existing.affiliations + new.affiliations).distinct(),
        status = new.status.ifBlank { existing.status },
        lastSeen = new.lastSeen.ifBlank { existing.lastSeen }
    )
}

fun mergeEventEntry(existing: EventEntry?, new: EventEntry): EventEntry {
    if (existing == null) return new
    return EventEntry(
        name = existing.name,
        description = if (new.description.isNotBlank()) "${existing.description}\n${new.description}" else existing.description,
        participants = (existing.participants + new.participants).distinct(),
        location = new.location.ifBlank { existing.location },
        aliases = (existing.aliases + new.aliases).distinct()
    )
}

fun mergeLocationEntry(existing: LocationEntry?, new: LocationEntry): LocationEntry {
    if (existing == null) return new
    return LocationEntry(
        name = existing.name,
        description = if (new.description.isNotBlank()) "${existing.description}\n${new.description}" else existing.description,
        controller = new.controller.ifBlank { existing.controller },
        aliases = (existing.aliases + new.aliases).distinct()
    )
}

fun mergeItemEntry(existing: ItemEntry?, new: ItemEntry): ItemEntry {
    if (existing == null) return new
    return ItemEntry(
        name = existing.name,
        description = if (new.description.isNotBlank()) "${existing.description}\n${new.description}" else existing.description,
        owner = new.owner.ifBlank { existing.owner },
        aliases = (existing.aliases + new.aliases).distinct()
    )
}

fun mergeFactionEntry(existing: FactionEntry?, new: FactionEntry): FactionEntry {
    if (existing == null) return new
    return FactionEntry(
        name = existing.name,
        description = if (new.description.isNotBlank()) "${existing.description}\n${new.description}" else existing.description,
        leader = new.leader.ifBlank { existing.leader },
        aliases = (existing.aliases + new.aliases).distinct()
    )
}

fun mergeRelationshipEntry(existing: RelationshipEntry?, new: RelationshipEntry): RelationshipEntry {
    if (existing == null) return new
    return RelationshipEntry(
        entity1 = existing.entity1,
        entity2 = existing.entity2,
        type = new.type.ifBlank { existing.type },
        description = if (new.description.isNotBlank()) "${existing.description}\n${new.description}" else existing.description
    )
}

fun buildLorebookUpdateAgent(): Pipeline {
    val extractionPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setModel(BedrockConfig.qwen235B)
        setTemperature(0.7)
        setTopP(0.9)
        requireJsonPromptInjection()
        setJsonOutput(LorebookExtraction::class)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        setPipeName("lorebook extraction pipe")

        setSystemPrompt("""You are a lorebook extraction agent for the game Autogenesis. ${BedrockConfig.gameDescription}
            |
            |Your job is to analyze the narrative text and extract ALL significant entities into structured data:
            |
            |**CHARACTERS**: Any named person, NPC, or significant entity
            |- Extract: name, description (role, traits, actions), aliases (nicknames, titles, roles)
            |- Aliases examples: "Commander Shepard" → ["Shepard", "Commander", "Alliance Commander"]
            |
            |**EVENTS**: Significant occurrences, battles, diplomatic actions, disasters
            |- Extract: name, description, participants, location, aliases (keywords, dates)
            |- Aliases examples: "Battle of Omega" → ["Omega battle", "Omega conflict", "Omega"]
            |
            |**LOCATIONS**: Places, territories, stations, facilities
            |- Extract: name, description, controller (who owns it), aliases (region, landmarks)
            |- Aliases examples: "Omega Station" → ["Omega", "station", "Terminus Systems"]
            |
            |**ITEMS**: Weapons, artifacts, resources, technology
            |- Extract: name, description, owner, aliases (type, keywords)
            |- Aliases examples: "Brown Singularity" → ["singularity", "brown", "entity"]
            |
            |**FACTIONS**: Organizations, nations, groups
            |- Extract: name, description, leader, aliases (ideology, territory)
            |- Aliases examples: "Systems Alliance" → ["Alliance", "human alliance", "Earth government"]
            |
            |**RELATIONSHIPS**: Connections between entities
            |- Extract: entity1, entity2, type (ally/enemy/neutral/subordinate), description
            |
            |##CRITICAL##
            |- Generate RICH alias lists for semantic matching (3-5 aliases per entity minimum)
            |- Include name variations, titles, roles, keywords, related terms
            |- Extract ALL entities mentioned, even briefly
            |- Be thorough but concise in descriptions
        """.trimMargin())

        setFooterPrompt("""Analyze the narrative and extract all entities into the JSON structure.
            |Focus on completeness and rich alias generation for semantic matching.
        """.trimMargin())

        setValidatorFunction {
            extractJson<LorebookExtraction>(it.text) != null
        }

        val branchPipe = BedrockMultimodalPipe().apply {
            useConverseApi()
            setRegion("us-west-2")
            setModel(BedrockConfig.PalmyraX5)
            setTemperature(0.7)
            setTopP(0.9)
            pullParentPipeContext()
            requireJsonPromptInjection()
            setJsonOutput(LorebookExtraction::class)
            setTokenBudget(BedrockConfig.palmyraBudgetSettings)
            allowEmptyContentObject()
            allowEmptyUserPrompt()
            setPipeName("lorebook extraction branch pipe")

            setPreInitFunction {
                it.text = it.getSnapshot()?.text.toString()
            }

            setValidatorFunction {
                extractJson<LorebookExtraction>(it.text) != null
            }

            setOnFailure { _, processed ->
                processed.text = serialize(LorebookExtraction())
                processed
            }
        }

        setBranchPipe(branchPipe)

        setTransformationFunction {
            val extraction = extractJson<LorebookExtraction>(it.text) ?: LorebookExtraction()
            val storyContext = ContextBank.getContextFromBank("story")

            var mergedCount = 0
            var newCount = 0

            // Merge characters
            for (char in extraction.characters) {
                val existing = storyContext.findLoreBookEntry(char.name)
                val merged = if (existing != null) {
                    mergedCount++
                    mergeCharacterEntry(deserialize<CharacterEntry>(existing.value), char)
                } else {
                    newCount++
                    char
                }
                storyContext.addLoreBookEntry(
                    key = char.name,
                    value = serialize(merged),
                    aliasKeys = merged.aliases
                )

                // Fuzzy match lorebook keys to players and update their history in the world state.
                WorldManager.updatePlayerHistoryDITL(merged.name, merged.aliases, merged.description)
            }

            // Merge events
            for (event in extraction.events) {
                val existing = storyContext.findLoreBookEntry(event.name)
                val merged = if (existing != null) {
                    mergedCount++
                    mergeEventEntry(deserialize<EventEntry>(existing.value), event)
                } else {
                    newCount++
                    event
                }
                storyContext.addLoreBookEntry(
                    key = event.name,
                    value = serialize(merged),
                    aliasKeys = merged.aliases
                )
            }

            // Merge locations
            for (loc in extraction.locations) {
                val existing = storyContext.findLoreBookEntry(loc.name)
                val merged = if (existing != null) {
                    mergedCount++
                    mergeLocationEntry(deserialize<LocationEntry>(existing.value), loc)
                } else {
                    newCount++
                    loc
                }
                storyContext.addLoreBookEntry(
                    key = loc.name,
                    value = serialize(merged),
                    aliasKeys = merged.aliases
                )
            }

            // Merge items
            for (item in extraction.items) {
                val existing = storyContext.findLoreBookEntry(item.name)
                val merged = if (existing != null) {
                    mergedCount++
                    mergeItemEntry(deserialize<ItemEntry>(existing.value), item)
                } else {
                    newCount++
                    item
                }
                storyContext.addLoreBookEntry(
                    key = item.name,
                    value = serialize(merged),
                    aliasKeys = merged.aliases
                )
            }

            // Merge factions
            for (faction in extraction.factions) {
                val existing = storyContext.findLoreBookEntry(faction.name)
                val merged = if (existing != null) {
                    mergedCount++
                    mergeFactionEntry(deserialize<FactionEntry>(existing.value), faction)
                } else {
                    newCount++
                    faction
                }
                storyContext.addLoreBookEntry(
                    key = faction.name,
                    value = serialize(merged),
                    aliasKeys = merged.aliases
                )
            }

            // Merge relationships
            for (rel in extraction.relationships) {
                val key = "${rel.entity1}-${rel.entity2}"
                val existing = storyContext.findLoreBookEntry(key)
                val merged = if (existing != null) {
                    mergedCount++
                    mergeRelationshipEntry(deserialize<RelationshipEntry>(existing.value), rel)
                } else {
                    newCount++
                    rel
                }
                storyContext.addLoreBookEntry(
                    key = key,
                    value = serialize(merged),
                    aliasKeys = listOf(rel.entity1, rel.entity2, rel.type)
                )
            }

            ContextBank.emplaceWithMutex("story", storyContext)
            Logger.info(LogCategory.SYSTEM, "[LOREBOOK] Updated story lorebook: $newCount new, $mergedCount merged")

            return@setTransformationFunction it
        }
    }

    return Pipeline().apply {
        add(extractionPipe)

        setPreValidationFunction { _, _, _ ->
            Logger.info(LogCategory.SYSTEM, "[LOREBOOK] Starting lorebook extraction and merge")
        }
    }
}