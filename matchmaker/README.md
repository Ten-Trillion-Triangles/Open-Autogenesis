# matchmaker

Custom gRPC matchmaker service for the Autogenesis PvP ladder. Sits between
AccelByte `match2` and the per-pool game sessions; consumes the cost
attributes stamped on every ticket by the server-extend `ServerConnector`
and proposes groups that maximize operator margin.

## Current state (v1)

- `MatchmakingAlgorithm.kt` — the cost-optimization + group-selection
  algorithm. Pure Kotlin, no transport types. Easy to unit-test.
- `MatchmakerServiceContract.kt` — the `MatchmakerServiceContract`
  interface plus a `DefaultMatchmakerService` implementation that wraps
  the algorithm. The contract mirrors the upstream
  `accelbyte.matchmaker.Service` proto (see
  `src/main/proto/accelbyte_matchmaker.proto`).
- `MatchmakerMain.kt` — entry point. In v1 it just runs the algorithm
  against a sample batch and logs the result. The gRPC transport
  follows.
- `Dockerfile` — JRE 24 base; the v1 binary writes logs only, but the
  port (`MATCHMAKER_GRPC_PORT`, default `9095`) is reserved for the
  follow-up gRPC binding.

## Wire shape

The proto in `src/main/proto/accelbyte_matchmaker.proto` is the source
of truth. The matchmaker exposes two RPCs:

- `Validate(Request) returns (Response)` — accepts or rejects a single
  ticket based on attribute sanity (subsidy in `[0, 4]`, known
  `cost_class`, etc.).
- `MakeMatches(stream Request) returns (stream Response)` — server
  streaming. The matchmaker consumes batches and streams back
  `Match` proposals with `match_attributes["max_players"]` set to the
  pool target (4 / 3 / 2).

## Cost attributes stamped by server-extend

`ServerConnector.executeLiveMatchmaking` enriches every ticket with:

| Key             | Type    | Source                                                    |
| --------------- | ------- | --------------------------------------------------------- |
| `cost_class`    | string  | `AccountSettings.costClass().name`                        |
| `cost_subsidy`  | int     | `AccountSettings.subsidyCapacity()` (0..4)                |
| `wallet_credits`| number  | `BillingStatus.credits`                                   |
| `byo_api_key`   | bool    | `AccountSettings.bringYourOwnApiKey`                      |

The matchmaker sorts candidates by `cost_subsidy` desc, then by the
`CostClass` rank (BYO_KEY < PRO < CASUAL < CREDIT < FREE), then by
`wallet_credits` desc, then by ticket id (deterministic tiebreak).

## Algorithm summary

1. Group tickets by `ladderFamily` (default `"pvp"`) so a `pvp-4`
   ticket is never paired with a `pvp-3` ticket.
2. Sort each family by subsidy desc → rank asc → credits desc → id.
3. Walk the sorted list and greedily build groups of the target size.
4. Skip a candidate when the group fails the policy's coverage check:
   - `pvp-4` / `pvp-3` (when `requireCoverageFor{N}=true`): the sum of
     `cost_subsidy` across the group must be `>= N`, or at least one
     ticket must be `BYO_KEY` (their LLM cost is on them).
   - `pvp-2` (default `requireCoverageFor2=false`): any 2 tickets match.

## Algorithm tests

`src/test/kotlin/org/ttt/autogenesis/matchmaker/MatchmakingAlgorithmTest.kt`
covers the happy path, coverage rejection, BYO_KEY exception, ladder
family isolation, and tiebreaks. Run with:

```bash
./gradlew :matchmaker:test
```

## Deploy (follow-up)

The `Dockerfile` produces a `matchmaker` image; `k8s/matchmaker.yaml`
will reference the gRPC port (9095) once the gRPC transport lands. The
operator registers the gRPC endpoint in the AccelByte match function
config; the bootstrap step in `MatchPoolBootstrap` writes
`match_function = "custom"` on each pool and the platform's match
function override routes the `make_matches` RPC to the registered URL.

## Tunables

`AlgorithmPolicy` controls coverage. Production knobs (env-vars on the
operator's choice; the v1 binary reads them at startup) are listed in
`server-extend`'s `SubsidyPolicy` and are mirrored here for the
matchmaker's read-only view:

- `MATCHMAKING_REQUIRE_COVERAGE_4` (default `true`)
- `MATCHMAKING_REQUIRE_COVERAGE_3` (default `true`)
- `MATCHMAKING_REQUIRE_COVERAGE_2` (default `false`)

The gRPC transport wiring (the actual `Service` impl that
`BindableService`-binds the gRPC port) is a follow-up deliverable; the
algorithm and tests are the value this PR ships.
