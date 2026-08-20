# Logging Patterns

## Autogenesis Custom Logger

This project uses a custom multi-platform logging system defined in `org.ttt.autogenesis.logging`.

### Imports
```kotlin
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
```

### Usage
```kotlin
Logger.info(LogCategory.GENERAL, "Your message here")
Logger.debug(LogCategory.NETWORK, "Network event: $data")
Logger.warn(LogCategory.DATABASE, "Slow query detected")
Logger.error(LogCategory.SYSTEM, "Critical system failure")
```

### Categories
Common categories include:
- `GENERAL`
- `NETWORK`
- `DATABASE`
- `UI`
- `AUTH`
- `SYSTEM`
- `LLM`

### Multi-line Logs
Multi-line messages are automatically converted to single lines using ` | ` as a separator by the `Logger` implementation.

## Injection Points

### Function Entry/Exit
Add logs at the start and end of critical functions to track execution flow.
```kotlin
fun processData(data: String) {
    Logger.debug(LogCategory.GENERAL, "processData entry: data length=${data.length}")
    // ... logic ...
    Logger.debug(LogCategory.GENERAL, "processData exit")
}
```

### Exception Handling
Always add logs in `catch` blocks.
```kotlin
try {
    performAction()
} catch (e: Exception) {
    Logger.error(LogCategory.GENERAL, "Action failed: ${e.message}")
    throw e
}
```

### State Changes
Log important state transitions.
```kotlin
status = newStatus
Logger.info(LogCategory.SYSTEM, "Status changed from $oldStatus to $newStatus")
```