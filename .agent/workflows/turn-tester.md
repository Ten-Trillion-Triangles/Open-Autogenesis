---
description: Workflow to execute a browser-driven turn test
---

// turbo-all
1. Start the server in the background: `./gradlew :server:run`
2. Start the client in the background:./gradlew:kvisionApp:jsBrowserDevelopmentRun`
3. Start the server-extend backend server in the background: `./gradlew :server-extend:run`
4. Wait for the client to be ready: `curl -v http://localhost:8080` (Ignore connection refused initially, wait for 200 OK)
5. Execute the browser test: Open `http://localhost:8080`.
   - **Note**: The application is configured to bypass the login screen and load the `GameplayUI` directly.
   - **IMPORTANT**: IGNORE AL NETWORK ERRORS via console (SSE, gRPC, REST). They are irrelevant to the success of this test.
   - Verify the `CommandBox` is visible.
   - Enter a command (e.g., "Analyze local sector") and click Send.
   - Verify that the turn resolution sequence begins.
   - Verify the turn resolution sequence completes (Turn increments).
   - Upon completion shut the client, and server processes down and free up the ports they are using.