# Android assistant architecture

The Android library lives in `android/assistant`. Packages separate chess rules,
application coordination, platform adapters, and presentation. The Dioxus UI
launches the native assistant through the existing JNI entry points.

| Package | Responsibility | Dependencies |
| --- | --- | --- |
| `domain.board` | Canonical positions, square-based moves, legality | Kotlin, domain |
| `domain.tracking` | Stable frames and legal turn transitions | Kotlin, domain |
| `engine.model` | Engine scores, candidates, and answers | Kotlin |
| `application.analysis` | Search lifecycle, cancellation, and result cache | Domain, engine models, JDK |
| `engine` | UCI parsing, FEN, and the Pikafish process | Domain, engine models, application port, Android |
| `recognition` | ONNX inference and normalized board geometry | Domain, Android, ONNX Runtime |
| `capture` | MediaProjection and leased frame buffers | Android, JDK |
| `presentation` | Chinese notation, score text, candidate snapshots, and overlays | Domain, engine models and codecs, geometry, Android |
| Root package | Service lifecycle, permissions, and stored settings | Application and adapters |

`AnalysisEngine` is the application port. `PikafishEngine` implements it; the
coordinator does not construct Android objects. `EngineAnswer`, `EngineCandidate`,
and `UciScore` contain protocol data; formatting extensions live in presentation.

## Model contracts

- `BoardPosition` uses a row-major 10-by-9 board, with black at the top. Uppercase
  pieces are red. `redToMove` always describes the next mover.
- `BoardMove` contains zero-based `from` and `to` squares. `UciNotation` alone
  converts between these coordinates and UCI strings. A legal move produces a
  new position and flips the turn without changing the original.
- `fromOrientedCells` normalizes either board orientation. Initial recognition
  validates piece placement before tracking determines the new turn. Full
  validation follows turn inference.
- `ObservedMove` stores the position before the move and the move itself.
  `Observation` contains state, while presentation supplies status and move text.
- `BoardTransitionResolver` accepts one legal move or two alternating legal
  moves. The first move can correct the manual turn seed. Later transitions
  reject animation rollback and consecutive moves by the same side.
- `StableBoardFilter` needs two matching samples for a new position. Invalid
  frames discard pending changes and preserve the confirmed baseline.
- `BoardGeometry` uses normalized display coordinates in A9, I9, A0, I0 order.
  `MoveHint` is a presentation model, separate from recognition output.

## Runtime ownership

The main thread owns service controls and overlay views. One recognition worker
owns the recognizer and board tracker. Capture leases each bitmap until the
recognition worker returns it. A separate engine worker owns searches. Generation
and request IDs reject stale callbacks after turn changes, cancellation, or close.

Recognition reuses a result only when dimensions, geometry, and every model-input
pixel match. Engine caching retains complete iterations keyed by position and
depth. Refactoring these boundaries must preserve both reuse contracts.

## Maintenance

Use short English comments for coordinate conventions, thread ownership, and
lifecycle constraints. Keep user-visible text in Chinese. Avoid comments that
repeat a method name or recount previous implementations. Bundled upstream source
retains its original comments and notices.

`verifyArchitecture` runs before Android builds and checks imports in domain,
engine-model, and application packages. Domain code cannot import Android,
engine protocols, or presentation. Application code can depend on the engine
models and `AnalysisEngine` port, but cannot import the Pikafish implementation.
The packages remain in one Android Gradle module.

The `justfile` is the project task entry point. `build.rs` delegates Android asset
preparation to the same `prepare-android` recipe. There are no separate project
Shell or Python task files. Generated Gradle wrappers and bundled upstream code
retain their original build tools.

```sh
just check
just test
just device-test
just apk-debug
just apk
```

Unit tests cover rules, turn tracking, UCI coordinates, notation, geometry, search
reuse, and cancellation. Device tests exercise Pikafish, overlays, and frame
ownership. `apk` exports an unsigned release build;
`release` signs it with credentials supplied through the environment.
