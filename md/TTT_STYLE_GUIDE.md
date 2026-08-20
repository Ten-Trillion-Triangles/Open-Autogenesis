# TTT Kotlin Style Guide

This guide replaces the deleted formatting rules and keeps every TTT repo consistent. Share it with agents before they touch Kotlin code.

## 1. Bracing

- **Constructs with parentheses**: Always put the opening brace on the line below when parentheses `()` are present. This indicates a proper scoped region that is sandboxed from upper scope.
  ```kotlin
  fun myFunction(param: String)
  {
      // body
  }
  
  class MyClass : BaseClass
  {
      // members
  }
  
  if(condition)
  {
      // ...
  }

  else
  {
      // ...
  }
  
  for(item in list)
  {
      // ...
  }
  
  when(command)
  {
      "foo" -> handleFoo()
      else -> handleDefault()
  }
  ```

- **Constructs without parentheses**: Place `{` to the right when no parentheses are present. This indicates inline/lambda behavior or Kotlin "cheating" methods that bypass normal scoping rules.
  ```kotlin
  init {
      // initialization code
  }
  
  val property get() {
      return value
  }
  
  companion object {
      // static members
  }
  ```

- **DSL builders and scope functions**: Always keep `{` on the same line regardless of parentheses, as these are inline constructs that don't create true isolated scopes.
  ```kotlin
  list.map { it.process() }
  
  hPanel(spacing = 10) {
      span("Hello") {
          color = Color.WHITE
      }
  }
  
  someObject.apply {
      property = value
  }
  // Widget tree functions - keep { on same line
  hPanel(spacing = 10) {
      span("Hello") {
          color = Color.WHITE
      }
      button("Click") {
          onClick { handleClick() }
      }
  }
  
  // Scope functions - keep { on same line  
  someObject.apply {
      property = value
  }
  ```

- **Regular Functions and Control Flow**: All other functions, classes, `if/else`, loops, and `when` statements follow the standard vertical bracing rule with `{` on the next line.
  ```kotlin
  fun regularFunction()
  {
      // body
  }
  
  if(condition)
  {
      // body
  }
  ```

## 2. Documentation

- **Every public function/method** must include a KDoc block describing what it does, every parameter, and the return value if applicable.
- **Classes** should have KDoc describing their purpose and key parameters.
- **Private functions** should have KDoc when they perform complex operations or have non-obvious behavior.
- Link to other relevant helpers or pipelines using square brackets.
  ```kotlin
  /**
   * Executes the pipeline for [PipelineProcessor].
   *
   * @param content Input that drives the pipeline.
   * @param context Optional context from [ContextBank].
   * @return The processed multimodal content.
   */
  fun runPipeline(content: MultimodalContent, context: ContextWindow?): MultimodalContent
  {
      // ...
  }
  ```
- In complex functions add inline comments that clarify non-obvious logic or trade-offs; simple getters/setters typically do not need extra comments beyond their KDoc.

## 3. Naming

- Variable names must be descriptive; avoid lazy, single-letter, abbreviated, or ambiguous names such as `x`, `tmp`, or `result`. Prefer names like `pipelineContext`, `requestPayload`, etc.
- Snake_case is forbidden across in-house code and string literals unless a third-party API literally requires that casing. Use `camelCase` for all Kotlin identifiers and uppercase with underscores for constants.

## 4. Type Declarations

- **Function parameters** must keep the type adjacent to the parameter name: `val count: Int`.
- **Class inheritance/interface** lists must be spaced with exactly one space before/after the colon: `class Child : Parent`.
- Avoid stray spaces elsewhere in type declarations.

## 5. Parentheses and Spacing

- Put the parentheses immediately next to control keywords, functions, and methods: `if(value)`, `for(element in list)`, `while(hasWork)`, `fun compute(value: String)`.
- Do not insert an extra space between the keyword and the opening `(`.

## 6. Additional Expectations

- Inline comments should explain why a block exists when business logic or concurrency interactions are non-trivial.
- When a rule conflicts with a forced compiler requirement (e.g., Kotlin DSL builder signatures), document the exception inline so future readers understand why the standard spacing was broken.
- **Widget tree exclusion**: When formatting UI code, exclude widget tree DSL functions from vertical bracing rules but apply all other formatting rules (spacing, naming, documentation).

Keep this guide near the top of markdown docs so other agents have it before editing Kotlin sources.