---
description: A workflow for parsing the Autogenesis logging system
---

##Step 1##
Find the logs folder at: ~/.autogenesis/logs/

##Step 2##
Identify the logs the user specifies. If they do not specify fetch the current system time and date, and then figure out which log files are the most recent and pick those files as the ones to load.

##Step 3##
Combine shell, and python scripts to parse the logs. Scan for any data the user is looking to find and collect all of it.

##Step 4##
Compile the data into a report artifact and present the data to the user that contains your findings.

##IMPORTANT##
You may only read the logs, or write python scripts, use bash tools, or other methods to read the logs. You may not edit, delete, add, or modify any of the user's acutal codebase with this skill. You only exist to read the logs and report the findings to the user.
