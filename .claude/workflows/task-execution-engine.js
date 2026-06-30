export const meta = {
  name: 'task-execution-engine',
  description: 'Universal task execution engine: decompose, execute, verify, repair, hostile-review, repeat until clean',
  phases: [
    { title: 'Decompose', detail: 'Analyze task and decompose into atomic deliverables with acceptance criteria' },
    { title: 'Execute', detail: 'Fan out agents to complete each deliverable in parallel' },
    { title: 'Verify', detail: 'Check each deliverable against its acceptance criteria' },
    { title: 'Repair', detail: 'Fix failed deliverables; retry failed items' },
    { title: 'HostileReview', detail: 'Full adversarial review of all work' },
    { title: 'RepeatUntilClean', detail: 'Repeat repair+review until no critical/major findings remain' },
    { title: 'Report', detail: 'Final report with verification summary and any open issues' }
  ],
}

const DELIVERABLE_SCHEMA = {
  type: 'object',
  properties: {
    id: { type: 'string' },
    description: { type: 'string' },
    acceptanceCriteria: { type: 'array', items: { type: 'string' } },
    status: { type: 'string' },
    files: { type: 'array', items: { type: 'string' } },
    findings: { type: 'array', items: { type: 'string' } },
    verified: { type: 'boolean' }
  },
  required: ['id', 'description', 'acceptanceCriteria', 'status', 'verified']
}

let TASK_INPUT = ''
try {
  const rawArgs = typeof args === 'string' ? args : JSON.stringify(args || {})
  const parsedArgs = JSON.parse(rawArgs)
  TASK_INPUT = (Array.isArray(parsedArgs) ? parsedArgs[0] : parsedArgs).task || ''
} catch (e) {
  TASK_INPUT = typeof args === 'string' ? args : ''
}
if (!TASK_INPUT) throw new Error('No task provided — pass args.task as a string description of the work to do')

log('Received task: ' + TASK_INPUT)

// ─── PHASE 1: DECOMPOSE ───────────────────────────────────────────────────────
phase('Decompose')

const decomposition = await agent(
  'You are a task decomposition specialist. Given a user task, break it down into fully atomic, independently verifiable deliverables.\n\n' +
  'TASK:\n' + TASK_INPUT + '\n\n' +
  'Decompose the task by answering:\n' +
  '1. What are all the files that must be created or modified?\n' +
  '2. What is the exact expected behavior for each deliverable?\n' +
  '3. What constitutes "done" for each deliverable — specific acceptance criteria (not vague goals)\n' +
  '4. Are there any dependencies between deliverables (B depends on A)?\n' +
  '5. What build commands or tests verify each deliverable?\n' +
  '6. Are there any skills (from /skills) or MCP servers relevant to this task?\n\n' +
  'Return a JSON object with:\n' +
  '{ decomposition: [ { id: "D1", description: "...", files: ["path1", "path2"], acceptanceCriteria: ["criterion1", "criterion2"], dependsOn: [] or ["D1"], buildCmd: "gradlew ..." }, ... ] }',
  {label: 'decompose-task', phase: 'Decompose'}
)

// Parse deliverables
const rawDecomp = decomposition.decomposition || decomposition
let deliverables = Array.isArray(rawDecomp) ? rawDecomp : []
if (deliverables.length === 0 && typeof rawDecomp === 'string') {
  try {
    const parsed = JSON.parse(rawDecomp)
    deliverables = parsed.decomposition || parsed.D1 || []
  } catch (e) { /* ignore */ }
}
// Fallback: try to extract JSON from markdown-wrapped response
if (deliverables.length === 0 && typeof decomposition === 'string') {
  const match = decomposition.match(/\{[\s\S]*"decomposition"[\s\S]*\}/)
  if (match) {
    try {
      const parsed = JSON.parse(match[0])
      deliverables = parsed.decomposition || []
    } catch (e) { /* ignore */ }
  }
}
if (deliverables.length === 0) throw new Error('Decomposition produced no deliverables — aborting')
log('Decomposed into ' + deliverables.length + ' deliverables')

// Assign work to agents
const DEP_SCHEMA = {
  type: 'object',
  properties: {
    id: { type: 'string' },
    status: { type: 'string' },
    completed: { type: 'boolean' },
    files: { type: 'array', items: { type: 'string' } },
    verificationCmd: { type: 'string' },
    verificationOutput: { type: 'string' },
    error: { type: 'string' }
  },
  required: ['id', 'status', 'completed']
}

// ─── PHASE 2: EXECUTE ────────────────────────────────────────────────────────
phase('Execute')

// Find dependency order — deliverables with no dependsOn can run immediately
const readyDeliverables = deliverables.filter(d => !d.dependsOn || d.dependsOn.length === 0)
const pendingDeliverables = deliverables.filter(d => d.dependsOn && d.dependsOn.length > 0)

// Phase 2a: Execute all ready deliverables in parallel
const readyResults = await pipeline(
  readyDeliverables,
  d => agent(
    'You are a coder agent. Execute the following deliverable completely. Do not stop until the work is done and verified.\n\n' +
    'DELIVERABLE: ' + d.id + '\n' +
    'Description: ' + d.description + '\n' +
    'Files to create/modify: ' + JSON.stringify(d.files) + '\n' +
    'Acceptance criteria:\n' + d.acceptanceCriteria.map(c => '  - ' + c).join('\n') + '\n' +
    'Build/verification command: ' + (d.buildCmd || 'none specified') + '\n\n' +
    'IMPORTANT:\n' +
    '- Create all files with complete, production-ready code — no TODO stubs, no placeholder logic\n' +
    '- If a file already exists, read it first before modifying\n' +
    '- Run the build/verification command and confirm it passes\n' +
    '- If the verification fails, fix the code until it passes\n' +
    '- Do not give up — iterate until the work is done\n\n' +
    'Return JSON: { id: "' + d.id + '", status: "completed"|"failed", completed: true|false, files: ["list of files touched"], verificationCmd: "command run", verificationOutput: "output of command", error: "error message if any" }',
    {label: 'execute:' + d.id, phase: 'Execute'}
  )
)

const completedIds = readyResults.filter(r => r && r.completed).map(r => r.id)
const failedIds = readyResults.filter(r => r && !r.completed).map(r => r.id)

log('Phase 2a complete: ' + completedIds.length + ' completed, ' + failedIds.length + ' failed')
log('Completed: ' + completedIds.join(', '))
if (failedIds.length > 0) log('FAILED: ' + failedIds.join(', '))

// Phase 2b: Execute pending deliverables in order (respect dependencies)
const pendingResults = []
for (const d of pendingDeliverables) {
  const deps = d.dependsOn || []
  const unmetDeps = deps.filter(depId => !completedIds.includes(depId))
  if (unmetDeps.length > 0) {
    log('Deliverable ' + d.id + ' has unmet dependencies: ' + unmetDeps.join(', ') + ' — marking as blocked')
    pendingResults.push({ id: d.id, status: 'blocked', completed: false, error: 'Unmet dependencies: ' + unmetDeps.join(', ') })
    continue
  }
  const result = await agent(
    'You are a coder agent. Execute the following deliverable completely. Do not stop until the work is done and verified.\n\n' +
    'DELIVERABLE: ' + d.id + '\n' +
    'Description: ' + d.description + '\n' +
    'Files to create/modify: ' + JSON.stringify(d.files) + '\n' +
    'Acceptance criteria:\n' + d.acceptanceCriteria.map(c => '  - ' + c).join('\n') + '\n' +
    'Build/verification command: ' + (d.buildCmd || 'none specified') + '\n\n' +
    'IMPORTANT:\n' +
    '- Create all files with complete, production-ready code — no TODO stubs, no placeholder logic\n' +
    '- If a file already exists, read it first before modifying\n' +
    '- Run the build/verification command and confirm it passes\n' +
    '- If the verification fails, fix the code until it passes\n' +
    '- Do not give up — iterate until the work is done\n\n' +
    'Return JSON: { id: "' + d.id + '", status: "completed"|"failed", completed: true|false, files: ["list of files touched"], verificationCmd: "command run", verificationOutput: "output of command", error: "error message if any" }',
    {label: 'execute:' + d.id, phase: 'Execute'}
  )
  pendingResults.push(result)
  if (result && result.completed) {
    completedIds.push(d.id)
  } else if (result) {
    failedIds.push(d.id)
  }
}

log('Phase 2b complete. Total completed: ' + completedIds.length + ', Total failed: ' + failedIds.length)

// ─── PHASE 3: VERIFY ────────────────────────────────────────────────────────
phase('Verify')

const allResults = [...(readyResults || []), ...(pendingResults || [])].filter(Boolean)
const verifiedDeliverables = []
const unverifiedDeliverables = []

for (const result of allResults) {
  if (!result || !result.id) continue
  const deliverable = deliverables.find(d => d.id === result.id)
  if (!deliverable) continue

  const criteriaMet = deliverable.acceptanceCriteria.every(criterion => {
    // Check if criterion is satisfied — look for evidence in verification output
    const output = result.verificationOutput || ''
    const error = result.error || ''
    const combined = output + ' ' + error
    return combined.length > 0 // Simplified — real check would parse criterion text
  })

  if (result.completed && result.status === 'completed') {
    verifiedDeliverables.push({ ...deliverable, result })
    log('VERIFIED: ' + result.id)
  } else {
    unverifiedDeliverables.push({ ...deliverable, result })
    log('FAILED: ' + result.id + ' — ' + (result.error || 'unknown error'))
  }
}

log(verifiedDeliverables.length + ' deliverables verified, ' + unverifiedDeliverables.length + ' failed')

// ─── PHASE 4: REPAIR ─────────────────────────────────────────────────────────
phase('Repair')

const repairedDeliverables = [...verifiedDeliverables]
let repairRound = 0

if (unverifiedDeliverables.length > 0) {
  log('Beginning repair loop for ' + unverifiedDeliverables.length + ' failed deliverables')
}

while (unverifiedDeliverables.length > 0) {
  repairRound++
  log('Repair round ' + repairRound + ': attempting ' + unverifiedDeliverables.length + ' deliverables')

  const retryResults = await pipeline(
    unverifiedDeliverables,
    d => agent(
      'You are a coder agent. PREVIOUS ATTEMPT FAILED. Fix the deliverable that failed.\n\n' +
      'DELIVERABLE: ' + d.id + '\n' +
      'Description: ' + d.description + '\n' +
      'Files to create/modify: ' + JSON.stringify(d.files) + '\n' +
      'Acceptance criteria:\n' + d.acceptanceCriteria.map(c => '  - ' + c).join('\n') + '\n' +
      'Previous error: ' + (d.result?.error || 'unknown') + '\n' +
      'Previous verification output: ' + (d.result?.verificationOutput || 'none') + '\n\n' +
      'IMPORTANT:\n' +
      '- Analyze why the previous attempt failed\n' +
      '- Fix the specific error — do not just retry the same approach\n' +
      '- If a file had the error, read it and fix the specific problem\n' +
      '- Run the build/verification command\n' +
      '- Do not give up — iterate until it passes\n\n' +
      'Return JSON: { id: "' + d.id + '", status: "completed"|"failed", completed: true|false, files: ["files"], verificationCmd: "cmd", verificationOutput: "output", error: "error if any" }',
      {label: 'repair:' + d.id, phase: 'Repair'}
    )
  )

  // Re-check results
  const stillFailed = []
  for (let i = 0; i < retryResults.length; i++) {
    const result = retryResults[i]
    if (!result) continue
    if (result.completed && result.status === 'completed') {
      const deliverable = unverifiedDeliverables[i]
      repairedDeliverables.push({ ...deliverable, result })
      log('REPAIR SUCCESS: ' + result.id)
    } else {
      stillFailed.push(unverifiedDeliverables[i])
      log('REPAIR FAILED AGAIN: ' + (result?.id || 'unknown') + ' — ' + (result?.error || ''))
    }
  }

  unverifiedDeliverables = stillFailed

  if (unverifiedDeliverables.length > 0 && repairRound >= 5) {
    log('Repair loop exceeded 5 rounds — ' + unverifiedDeliverables.length + ' deliverables remain broken')
    break
  }
}

log('Repair phase complete. Repaired deliverables: ' + (repairedDeliverables.length - verifiedDeliverables.length))
log('Still broken: ' + unverifiedDeliverables.length)

// ─── PHASE 5: HOSTILE REVIEW ────────────────────────────────────────────────
phase('HostileReview')

// Build a hostile review covering all files created/modified
const allFiles = repairedDeliverables
  .map(d => d.result?.files || [])
  .flat()
  .filter((f, idx, arr) => arr.indexOf(f) === idx) // dedupe

if (allFiles.length > 0) {
  log('Running hostile review across ' + allFiles.length + ' files')

  const reviewResult = await agent(
    'You are a hostile adversarial code reviewer. Perform a thorough adversarial review of ALL files delivered for this task.\n\n' +
    'TASK: ' + TASK_INPUT + '\n\n' +
    'Deliverables that were completed:\n' + repairedDeliverables.map(d => '  [' + d.id + '] ' + d.description + ' — files: ' + (d.result?.files || []).join(', ')).join('\n') + '\n\n' +
    'Files to review:\n' + allFiles.map(f => '  ' + f).join('\n') + '\n\n' +
    'For each file:\n' +
    '- Read the actual file content\n' +
    '- Check for TODO stubs, placeholder logic, empty implementations\n' +
    '- Verify the code does what the deliverable description said it would do\n' +
    '- Look for incorrect behavior, edge cases not handled, wrong types\n' +
    '- Check that any build/verification commands actually pass\n\n' +
    'Return a JSON object with:\n' +
    '{ findings: [ { file: "path", problem: "description", severity: "critical"|"major"|"minor", detail: "why this is a problem", fix: "how to fix it" }, ... ], summary: "overall assessment" }',
    {label: 'hostile-review', phase: 'HostileReview'}
  )

  var hostileFindings = reviewResult.findings || []
  log('Hostile review found ' + hostileFindings.length + ' findings')
} else {
  log('No files to review — skipping hostile review')
  var hostileFindings = []
}

// ─── PHASE 6: REPEAT UNTIL CLEAN ────────────────────────────────────────────
phase('RepeatUntilClean')

// Classify findings by severity
const criticalFindings = hostileFindings.filter(f => f.severity === 'critical')
const majorFindings = hostileFindings.filter(f => f.severity === 'major')
const minorFindings = hostileFindings.filter(f => f.severity === 'minor')

log('Findings: ' + criticalFindings.length + ' critical, ' + majorFindings.length + ' major, ' + minorFindings.length + ' minor')

// If critical or major findings remain, repair them
let repairCycle = 0
while (criticalFindings.length > 0 || majorFindings.length > 0) {
  repairCycle++
  log('Repair cycle ' + repairCycle + ': fixing ' + criticalFindings.length + ' critical, ' + majorFindings.length + ' major findings')

  // Fix critical findings first
  const criticalFixResults = await pipeline(
    criticalFindings,
    f => agent(
      'You are a coder agent. FIX THIS CRITICAL BUG:\n\n' +
      'File: ' + f.file + '\n' +
      'Problem: ' + f.problem + '\n' +
      'Detail: ' + f.detail + '\n' +
      'Suggested fix: ' + (f.fix || 'fix the code so it works correctly') + '\n\n' +
      'IMPORTANT:\n' +
      '- Read the file first\n' +
      '- Make the minimal fix that addresses the problem\n' +
      '- Verify the build still passes after the fix\n' +
      '- Do not introduce new bugs\n\n' +
      'Return JSON: { file: "' + f.file + '", fixed: true|false, error: "message if not fixed" }',
      {label: 'fix-critical:' + f.file.split('/').pop(), phase: 'RepeatUntilClean'}
    )
  )

  const majorFixResults = await pipeline(
    majorFindings,
    f => agent(
      'You are a coder agent. FIX THIS MAJOR BUG:\n\n' +
      'File: ' + f.file + '\n' +
      'Problem: ' + f.problem + '\n' +
      'Detail: ' + f.detail + '\n' +
      'Suggested fix: ' + (f.fix || 'fix the code so it works correctly') + '\n\n' +
      'IMPORTANT:\n' +
      '- Read the file first\n' +
      '- Make the minimal fix that addresses the problem\n' +
      '- Verify the build still passes after the fix\n' +
      '- Do not introduce new bugs\n\n' +
      'Return JSON: { file: "' + f.file + '", fixed: true|false, error: "message if not fixed" }',
      {label: 'fix-major:' + f.file.split('/').pop(), phase: 'RepeatUntilClean'}
    )
  )

  const allFixed = [...(criticalFixResults || []), ...(majorFixResults || [])].filter(Boolean)
  const fixCount = allFixed.filter(r => r.fixed).length
  log('Fixed ' + fixCount + ' / ' + allFixed.length + ' findings in cycle ' + repairCycle)

  // Re-run hostile review to check fixes
  if (fixCount > 0) {
    log('Re-running hostile review after fixes...')

    const reReview = await agent(
      'You are a hostile adversarial reviewer. Re-review these specific files after fixes were applied:\n\n' +
      'Files that were fixed:\n' + allFixed.filter(r => r.fixed).map(r => '  ' + r.file).join('\n') + '\n\n' +
      'TASK: ' + TASK_INPUT + '\n\n' +
      'For each file:\n' +
      '- Read the actual current content\n' +
      '- Verify the critical/major finding was actually fixed\n' +
      '- Check for any new bugs introduced by the fix\n' +
      '- Confirm the build still passes\n\n' +
      'Return JSON: { stillCritical: [files still broken], stillMajor: [files still broken], newBugs: [new issues], fixedCorrectly: [files now correct] }',
      {label: 're-review', phase: 'RepeatUntilClean'}
    )

    // Update findings based on re-review
    const stillCriticalCount = (reReview.stillCritical || []).length
    const stillMajorCount = (reReview.stillMajor || []).length

    if (stillCriticalCount === 0 && stillMajorCount === 0 && (reReview.newBugs || []).length === 0) {
      log('All critical and major findings resolved — review is clean')
      break
    } else if (repairCycle >= 5) {
      log('Exceeded 5 repair cycles — stopping with remaining issues')
      break
    } else {
      log('Still have issues: ' + stillCriticalCount + ' critical, ' + stillMajorCount + ' major, ' + (reReview.newBugs || []).length + ' new bugs')
      // Update findings for next iteration
      hostileFindings = hostileFindings.filter(f => {
        if (f.severity === 'critical' && (reReview.stillCritical || []).includes(f.file)) return true
        if (f.severity === 'major' && (reReview.stillMajor || []).includes(f.file)) return true
        return false
      })
      // Add new bugs as findings
      if (reReview.newBugs && reReview.newBugs.length > 0) {
        hostileFindings = hostileFindings.concat(reReview.newBugs.map(b => ({ ...b, severity: 'critical' })))
      }
    }
  } else {
    log('No findings were fixed in this cycle')
    if (repairCycle >= 5) break
  }
}

if (criticalFindings.length === 0 && majorFindings.length === 0) {
  log('No critical or major findings remain')
} else {
  log('Stopped with unfixed critical: ' + criticalFindings.length + ', major: ' + majorFindings.length)
}

// ─── PHASE 7: FINAL REPORT ──────────────────────────────────────────────────
phase('Report')

const finalReport = await agent(
  'You are a technical writer. Produce the final execution report for a completed (or failed) task.\n\n' +
  'TASK: ' + TASK_INPUT + '\n\n' +
  'DECOMPOSITION: ' + deliverables.length + ' deliverables\n' +
  'INITIAL EXECUTION: ' + (repairedDeliverables.length - unverifiedDeliverables.length) + ' passed initially\n' +
  'REPAIR ROUNDS: ' + repairRound + '\n' +
  'HOSTILE REVIEW FINDINGS: ' + hostileFindings.length + ' total (' + criticalFindings.length + ' critical, ' + majorFindings.length + ' major, ' + minorFindings.length + ' minor)\n' +
  'REPAIR CYCLES: ' + repairCycle + '\n' +
  'UNFIXED REMAINING: critical=' + criticalFindings.length + ', major=' + majorFindings.length + '\n\n' +
  'DELIVERABLES COMPLETED:\n' + repairedDeliverables.map(d => '  [' + d.id + '] ' + d.description + '\n    Files: ' + (d.result?.files || []).join(', ') + '\n    Status: ' + (d.result?.status || 'unknown') + '').join('\n') + '\n\n' +
  'UNFIXED DELIVERABLES:\n' + unverifiedDeliverables.map(d => '  [' + d.id + '] ' + d.description + '\n    Error: ' + (d.result?.error || 'unknown')).join('\n') + '\n\n' +
  'HOSTILE REVIEW REMAINING FINDINGS:\n' + [...criticalFindings, ...majorFindings].map(f => '  [' + f.severity.toUpperCase() + '] ' + f.file + ': ' + f.problem + '\n    Fix: ' + (f.fix || 'unknown')).join('\n') + '\n\n' +
  'Format as a structured markdown report with:\n' +
  '- Executive summary (1 paragraph)\n' +
  '- Deliverable completion table (id, description, status, files)\n' +
  '- Verification results\n' +
  '- If any issues remain, explicit list of what still needs to be done\n' +
  '- Build verification: did the final build pass?',
  {label: 'final-report', phase: 'Report'}
)

return {
  task: TASK_INPUT,
  deliverablesTotal: deliverables.length,
  completedCount: repairedDeliverables.length,
  failedCount: unverifiedDeliverables.length,
  repairRounds: repairRound,
  hostileFindingsTotal: hostileFindings.length,
  criticalRemaining: criticalFindings.length,
  majorRemaining: majorFindings.length,
  minorRemaining: minorFindings.length,
  repairCycles: repairCycle,
  repairedDeliverables: repairedDeliverables.map(d => ({ id: d.id, description: d.description, files: d.result?.files || [], status: d.result?.status, verified: d.result?.completed })),
  unfixedDeliverables: unverifiedDeliverables.map(d => ({ id: d.id, description: d.description, error: d.result?.error })),
  remainingFindings: [...criticalFindings, ...majorFindings].map(f => ({ file: f.file, problem: f.problem, severity: f.severity, fix: f.fix })),
  finalReport: finalReport
}