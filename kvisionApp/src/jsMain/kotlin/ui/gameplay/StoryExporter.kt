package ui.gameplay

import kotlinx.coroutines.yield
import structs.GameHistory

/**
 * Utility for exporting game history into human-readable formats.
 */
object StoryExporter
{
    /**
     * Converts a list of [GameHistory] entries into a formatted, human-readable string.
     * This is a suspend function that yields to the UI thread periodically to prevent hitching
     * during the processing of very large history logs.
     *
     * @param history The list of turns to process.
     * @param gameName Optional name of the game world for the header.
     * @return A multi-line string containing the full story of the game.
     */
    suspend fun exportStoryToText(history: List<GameHistory>, gameName: String? = null): String
    {
        val sb = StringBuilder()
        
        // 1. Header
        sb.append("====================================================\n")
        val title = gameName?.uppercase() ?: "THE WORLD"
        sb.append("   AUTOGENESIS: CHRONICLES OF $title\n")
        sb.append("====================================================\n\n")
        
        if (history.isEmpty())
        {
            sb.append("The chronicles are empty. The world has yet to be shaped.\n")
            return sb.toString()
        }

        // 2. Iterate through turns
        history.forEachIndexed { index, turn ->
            val turnNumber = index + 1
            
            sb.append("----------------------------------------------------\n")
            sb.append("TURN $turnNumber: ${turn.turnPlayer}\n")
            sb.append("----------------------------------------------------\n")
            
            sb.append("ACTION:\n")
            sb.append("${turn.turnAction}\n\n")
            
            if (turn.turnStory.isNotBlank())
            {
                sb.append("STORY:\n")
                sb.append("${turn.turnStory}\n\n")
            }
            
            if (turn.turnResult.isNotBlank())
            {
                val outcome = if (turn.wasPlayerSuccessful) "[SUCCESS]" else "[FAILURE]"
                sb.append("RESULT $outcome:\n")
                sb.append("${turn.turnResult}\n")
            }
            
            sb.append("\n")
            
            // Yield to the UI thread every few entries to prevent blocking the JS main loop
            if (turnNumber % 5 == 0)
            {
                yield()
            }
        }
        
        // 3. Footer
        sb.append("====================================================\n")
        sb.append("   END OF CHRONICLES\n")
        sb.append("====================================================\n")
        
        return sb.toString()
    }
}