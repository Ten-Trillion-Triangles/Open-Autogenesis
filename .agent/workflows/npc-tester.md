---
description: Workflow to execute a browser-driven NPC turn test
---

// turbo-all
1. Start the server in the background: `./gradlew :server:run`
2. Start the client in the background: `./gradlew :kvisionApp:jsBrowserDevelopmentRun`
3. Wait for the client to be ready: `curl -v http://localhost:8080` (Ignore connection refused initially, wait for 200 OK)
4. Execute the browser test: Open `http://localhost:8080`.
   - **Note**: The application is configured to bypass the login screen and load the `GameplayUI` directly.
   - **IMPORTANT**: IGNORE ALL NETWORK ERRORS via console (SSE, gRPC, REST). They are irrelevant to the success of this test.
   - Verify the `CommandBox` is visible.
   - Enter the debug command: `/npc TestBot launches a surprise attack on the southern border` and click Send.
   - Verify that the NPC is created (see Server Logs) and the turn resolution sequence begins (Progress bar updates).
   - Verify the narrative streams (chunks appear).
   - Verify the turn resolution sequence completes (Turn increments or message "NPC turn complete").
   - Upon completion, examine the trace files in `.tpipe/debug/trace` (specifically `NPC_Validation`, `NPC_PlayType`, `NPC_TargetDetectors`, `NPC_TurnSplitter`) to ensure no errors occurred.
   - Shut the client and server processes down and free up the ports they are using.
