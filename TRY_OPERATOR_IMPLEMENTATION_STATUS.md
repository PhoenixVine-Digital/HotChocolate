# Try Operator (`?`) — Implementation Status

**Done — implemented and shipped in `selfhost/`, 2026-09-04.** This
document was written as a pre-implementation task-planning guide (time
estimates, step-by-step next actions); everything it describes as
"next steps" has been completed. See README's own "The `?` operator"
section for the real, current design and verification, and
`IMPLEMENTING_TRY_OPERATOR.md`'s own updated status banner for the two
places the actual implementation deviated from this plan's own
assumptions. `examples/try_operator.hc` (referenced below) is real and
passing, fixed to use the fully-qualified `Result<Int, String>::Ok/Err`
construction form two-type-parameter generics actually need (see
README's own "Two-type-parameter generics" section) rather than the
bare `Result::Ok`/`Result::Err` this doc originally sketched. The rest
of this document is kept as a historical planning record, not a live
task list.

## What We've Prepared

1. **IMPLEMENTING_TRY_OPERATOR.md** — Complete specification and implementation guide
   - Detailed scope and feature description
   - Step-by-step implementation instructions for each compiler phase
   - Code snippets and pseudo-code showing what to add
   - Testing strategy with 5 test cases
   - Known limitations and future work

2. **examples/try_operator.hc** — Reference example showing the feature in action
   - Once implemented, this example should compile and run correctly
   - Demonstrates both success and error cases

## Architecture Challenge

Hot Chocolate is a **self-hosted compiler** — the compiler is written in Hot Chocolate itself. The implementation is split across:

- **selfhost/*.hotc** — Representative subset of the compiler source (for demonstration)
- **selfhost/bootstrap/*.class** — Pre-compiled bytecode that compiles selfhost files
- **Actual compiler** — Compiled from selfhost/*.hotc using the bootstrap

To implement the `?` operator, you need to:

1. Modify the selfhost source files:
   - `selfhost/ast/Ast.hotc` — Add `Expr.Try { expr: Expr }`
   - `selfhost/parser/Parser.hotc` — Recognize postfix `?`
   - `selfhost/checker/Checker.hotc` — Validate Result types and function context
   - `selfhost/codegen/Codegen.hotc` — Generate match bytecode

2. Recompile the compiler (bootstrap cycle):
   - Use existing bootstrap to compile updated selfhost files
   - This creates a new bootstrap that includes `?` support
   - Use new bootstrap to recompile (verify it's self-hosting correctly)

## Implementation Effort

**Estimated:** 2-4 days of focused work

**Breakdown:**
- Parser changes: ~2 hours (straightforward, similar to existing postfix operators)
- Checker changes: ~4-6 hours (type validation, function context tracking)
- Codegen changes: ~4-8 hours (match desugaring, bytecode generation)
- Testing & debugging: ~4-8 hours

**Total:** ~14-24 hours of development time

## Next Steps

### Immediate (0-1 hours)

1. **Read the specification** — IMPLEMENTING_TRY_OPERATOR.md has everything you need
2. **Understand the self-hosted architecture** — Review selfhost/*.hotc structure
3. **Set up a development environment**:
   ```bash
   # Build the current compiler
   ./gradlew build
   
   # Try modifying selfhost files (backup first!)
   cp selfhost/ast/Ast.hotc selfhost/ast/Ast.hotc.backup
   ```

### Step 1: Parser (1-2 hours)

- Open `selfhost/parser/Parser.hotc`
- Find the method handling postfix operators (likely `postfix()` or `call_or_primary()`)
- Add a loop to recognize and consume `QUESTION` tokens after primary expressions
- Update the return to wrap in `Expr.Try`

**Verification:** Try to parse `examples/try_operator.hc` — it should recognize the `?` syntax even if checking/codegen fails

### Step 2: Checker (2-4 hours)

- Open `selfhost/checker/Checker.hotc`
- Add `current_fn_return_type: Option<Ty>` field to track function context
- Update function checking to set this field before checking the body
- Add `check_try` method (see pseudocode in IMPLEMENTING_TRY_OPERATOR.md)
- Wire it into the main `check_expr` dispatcher

**Verification:** Should give clear error messages for invalid `?` usage:
- Used outside Result-returning function
- Wrong error type
- Applied to non-Result type

### Step 3: Codegen (2-4 hours)

- Open `selfhost/codegen/Codegen.hotc`
- Add `gen_try` method to handle `Expr.Try`
- Implement match desugaring (load Result, isinstance checks, field extraction, early return)

**Verification:** Should produce bytecode equivalent to the match desugaring shown in the spec

### Step 4: Bootstrap Cycle (1-2 hours)

```bash
# Test compilation of examples/try_operator.hc
./gradlew run --args="run examples/try_operator.hc"

# If successful, rebuild bootstrap
./gradlew installDist

# Verify self-hosting (new compiler should compile itself)
./gradlew run --args="build selfhost/ out"
javap -c out/SelfhostCLI.class | head -50
```

### Step 5: Testing (1-2 hours)

Run all 5 test cases from IMPLEMENTING_TRY_OPERATOR.md:
1. Basic unwrap and early return
2. Chained `?` operators
3. Type mismatch error
4. Return type mismatch error
5. Outside Result-returning function

## Helpful Resources

- **Lexer:** Already handles `?` as QUESTION token — no changes needed
- **Error handling comparison:**
  - Rust's `?` operator: https://doc.rust-lang.org/edition-guide/rust-2015/error-handling/try-operator.html
  - This language's try/catch: Already supports JVM exceptions
- **Related:** `if let` operator (already shipped) uses similar pattern-matching desugaring

## Blockers / Known Issues

None expected. The infrastructure (pattern matching, early returns, monomorphic generics) all already exists and works.

## Success Criteria

Once complete, these should all work:

```hc
fn load_texture(path: String) -> Result<Texture, LoadError> {
    let bytes = read_file(path)?;
    let tex = decode(bytes)?;
    return Result::Ok { value: tex };
}
```

- Compiles without error
- Returns Err on first failing step
- Returns Ok with final value on success
- Gives clear compiler errors for invalid uses

## Questions?

Refer to IMPLEMENTING_TRY_OPERATOR.md for detailed implementation guidance, or start by exploring the selfhost source files to understand the current architecture better.
