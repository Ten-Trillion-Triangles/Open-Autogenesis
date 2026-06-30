package org.ttt.autogenesis.server

/**
 * Exception thrown when a client attempts to bind a session to a server that is
 * currently draining. The server enters draining state upon receiving a drain signal
 * from DSM and stops accepting new session binds while allowing existing sessions
 * to complete.
 *
 * @param message The error message describing why the bind was rejected.
 */
class ServerDrainingException(
    message: String = "Server is draining, not accepting new sessions"
) : Exception(message)