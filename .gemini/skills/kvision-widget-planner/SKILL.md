---
name: kvision-widget-planner
description: "Helps plan KVision widgets by gathering requirements, style preferences, and generating high-quality mockups using the nanobanana extension, then researches, creates a steering document, and generates an implementation plan."
---

# KVision Widget Planner Workflow

## Step 1: Requirement Gathering and Mockups

The user will request help planning the creation or modification of a KVision widget. You must:
- Discuss and gather all requirements from the user.
- Ask about the desired style and incorporate that information.
- **Mock-up Generation with Nanobanana**: Use the `generate_image`, `generate_diagram`, and `generate_icon` tools to create high-quality, visually appealing mockups of the proposed UI.
    - Use `generate_image` for detailed visual concepts, specifying styles and variations to match the user's requirements.
    - Use `generate_diagram` with `type="wireframe"` for structural UI layouts.
    - Use `generate_icon` for custom UI elements or app icons.
    - Always follow the `nanobanana` core principles: precise count adherence, style/variation compliance, and visual consistency as specified in the extension context.
- Iterate with the user until they indicate that this phase is complete.

## Step 2: Research and Validation

- Research the existing codebase to understand how successful KVision widgets are implemented.
- Use web search to thoroughly document and understand KVision.
- Provide grounding evidence for your research to avoid hallucination.
- Validate that you are not using "cowboy coding" practices such as raw DOM writes, CSS hacks, HTML hacks, or any other methods that bypass KVision's correct implementation.

## Step 3: Steering Document Generation

- Generate a steering document to guide the implementation process and ensure adherence to KVision rules.
- This document should confirm understanding of what is needed for correct implementation and keep the requirements at the forefront.

## Step 4: Implementation Plan Generation

- Generate a detailed plan that the user can review and iterate on.
- Ensure the plan incorporates the steering document and requirements gathered.
- This plan should be suitable for invoking the `ui-builder` workflow for implementation.