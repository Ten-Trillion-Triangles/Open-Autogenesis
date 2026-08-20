---
name: tpipe-context
description: "Locates the TPipe library, answers questions about its functionality, and provides examples from projects like Autogenesis, TPipeWriter, and TStep, while also consulting official TPipe documentation."
---

# TPipe-Context Skill

This skill is designed to:
1.  **Locate the TPipe library** on the local system, starting the search from `<workspaces-dir>`.
2.  **Answer questions** about how TPipe works.
3.  **Dive into projects** made using TPipe such as Autogenesis, TPipeWriter, and TStep to gain real-world working examples of its usage.
4.  **Consult official documentation** (via local files or internal URLs) for TPipe when answering questions. **Note: Google Search is not effective for finding information about this specialized library.**

When asked about TPipe, this skill will first attempt to locate the library by searching the disk (specifically `<workspaces-dir>`). It will then use the information gathered from the provided project contexts (Autogenesis, TPipeWriter, TStep) and any accessible documentation to answer user queries about TPipe's architecture, components, and usage patterns.