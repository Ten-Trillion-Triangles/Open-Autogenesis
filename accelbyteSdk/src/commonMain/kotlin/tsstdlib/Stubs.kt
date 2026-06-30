package tsstdlib

import kotlin.js.Json

/**
 * External interface representing the result of a regular expression execution.
 * 
 * This interface provides access to the results of executing a regular expression
 * against a string, including the matched text, capture groups, and position
 * information. It mirrors the JavaScript RegExpExecArray interface.
 */
external interface RegExpExecArray 
{
    /**
     * The original input string that was searched.
     * 
     * @return [String] The input string, or null if not available
     */
    val input : String?
    
    /**
     * Named capture groups from the regular expression match.
     * 
     * Contains key-value pairs for named groups defined in the regex pattern.
     * Only available when using named capture groups in the pattern.
     * 
     * @return [Json] Object containing named groups, or null if none
     */
    val groups : Json?
    
    /**
     * Zero-based index of the match in the input string.
     * 
     * Indicates the position where the matched substring begins within
     * the original input string.
     * 
     * @return [Int] Starting position of the match
     */
    val index : Int
    
    /**
     * Index after the last matched character.
     * 
     * Points to the position immediately following the end of the
     * matched substring in the input string.
     * 
     * @return [Int] Position after the match ends
     */
    val lastIndex : Int
}

/**
 * Type alias for a record/dictionary structure with key-value pairs.
 * 
 * Represents a JavaScript object with keys of type K and values of type V.
 * This is commonly used for dynamic object structures in TypeScript/JavaScript
 * interop scenarios.
 * 
 * @param K The type of keys in the record
 * @param V The type of values in the record
 */
typealias Record<K, V> = Json

/**
 * Type alias for a partial object where all properties are optional.
 * 
 * Represents a TypeScript Partial<T> type, making all properties of T optional.
 * Useful for update operations or configuration objects where not all
 * properties need to be specified.
 * 
 * @param T The original type to make partial
 */
typealias Partial<T> = Json

/**
 * Type alias for an object with certain properties omitted.
 * 
 * Represents a TypeScript Omit<T, K> type, creating a new type by
 * excluding specified properties from the original type. Used for
 * creating derived types without certain fields.
 * 
 * @param T The original type to omit properties from
 * @param K The keys/properties to omit from T
 */
typealias Omit<T, K> = Json
