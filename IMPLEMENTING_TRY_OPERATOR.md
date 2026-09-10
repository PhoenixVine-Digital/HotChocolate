# Implementing the `?` Operator (Try Operator)

**Status: implemented and shipped in `selfhost/`, 2026-09-04** — see
README's own "The `?` operator" section for the current design,
verification, and the two deliberate deviations from this doc's own
spec: the AST node is `Expr.TryOp`, not `Expr.Try` (a real naming
collision with the pre-existing `Stmt.Try` — variant names share one
flat namespace across every `enum` in this compiler, see `Ast.hc`'s own
`Expr.TryOp` header), and `Result`'s actual `selfhost/` codegen
representation is a single tagged class (`tag: Int` + mangled
`Variant$field` fields for every variant, like every other enum this
compiler emits), not the separate-subclass-per-variant shape this
document's own bytecode sketch in step 4.2 assumed — `gen_try_op`
reads `tag` and the mangled fields directly instead. The rest of this
document (scope, desugaring rule, test cases) matches what actually
shipped and is kept as the implementation record, not a live spec to
re-derive from.

## Overview

The `?` operator is a postfix operator that unwraps `Result<T, E>` values, enabling early return on error. This document details exactly what needs to be implemented.

**Scope:** This feature desugars `expr?` into a match that unwraps `Ok { value }` or early-returns `Err { error }` from the enclosing function.

**No error conversion:** v1 scope excludes Rust-style `From` trait conversions — the error type must match exactly.

## Feature Specification

### Syntax

```hc
fn load_texture(path: String) -> Result<Texture, LoadError> {
    let bytes = read_file(path)?;   // unwraps Ok, or returns Err
    let tex = decode(bytes)?;       // same
    return Result::Ok { value: tex };
}
```

### Desugaring

`expr?` desugars to:

```hc
match expr {
    Ok { value } => value,
    Err { error } => return Result::Err { error: error },
}
```

### Constraints

1. **Function return type:** `?` is only valid inside functions whose return type is `Result<T, E>`
2. **Error type match:** The expression's error type must exactly match the function's error type (no implicit conversion)
3. **Value binding:** The unwrapped value is the result of the `?` expression
4. **Move semantics:** The Result value is moved (consumed) by the `?` operator

## Implementation Steps

### 1. Lexer (selfhost/lexer/Lexer.hotc)

**Status:** Already done. The `QUESTION` token already exists and is recognized as `?`.

No changes needed here.

### 2. Parser (selfhost/parser/Parser.hotc)

#### 2.1 Add `Expr.Try` to AST

In `selfhost/ast/Ast.hotc`, add to the `Expr` enum:

```hc
enum Expr {
    // ... existing variants ...
    Try { expr: Expr },  // expr?
}
```

#### 2.2 Recognize `?` as postfix operator

In `Parser.hotc`, in the expression parsing (likely `call_or_primary` or `postfix` method), after parsing a primary expression, check for `?`:

```hc
// Pseudo-code for postfix parsing
fn postfix(self) -> Expr {
    var result = self.call_or_primary();
    
    while self.check(QUESTION) {
        self.advance();  // consume ?
        result = Expr::Try { expr: result };
    }
    
    return result;
}
```

**Note:** The postfix parsing loop allows `expr??` (though unusual), which desugars to nested tries. This is correct by the desugaring rule.

#### 2.3 Ensure precedence is correct

The `?` operator should be postfix and have high precedence, applying after:
- Field access (`.`)
- Method calls
- Array indexing

Before:
- Binary operators (`+`, `-`, `&&`, etc.)
- Assignment

Example: `obj.field()?.get_value()` should parse as `((obj.field())?.get_value())`, not `obj.field()?(...)`.

### 3. Checker (selfhost/checker/Checker.hotc)

#### 3.1 Track function return type

Before checking a function body, store the function's return type in checker scope. When we see a `?` operator, we can validate against it.

#### 3.2 Check `Expr.Try` variant

When checking an `Expr.Try { expr }`:

```hc
fn check_try(self, expr: Expr, line: Int) -> Ty {
    // Check the inner expression
    var result_ty = self.check_expr(expr);
    
    // Must be a Result type
    // Expected format: "Result$T$E" (monomorphized name)
    if !result_ty.is_result() {
        self.error("? operator requires Result<T, E> type, but got {result_ty}");
    }
    
    // Extract T and E from "Result$T$E"
    var t = result_ty.extract_result_ok_type();
    var e = result_ty.extract_result_err_type();
    
    // Verify the enclosing function returns Result<_, E>
    if self.current_fn_return_type == none() {
        self.error("? operator can only be used in functions with Result return type");
    }
    
    var fn_return_ty = self.current_fn_return_type.unwrap();
    if !fn_return_ty.is_result() {
        self.error("? operator can only be used in functions returning Result");
    }
    
    var fn_return_e = fn_return_ty.extract_result_err_type();
    if e != fn_return_e {
        self.error("? operator error type {e} doesn't match function return type {fn_return_e}");
    }
    
    // The result type of expr? is T (the Ok value)
    return t;
}
```

#### 3.3 Move checking

The `?` operator consumes the Result value (it's moved). The move checker should see this as a move of the inner expression's value.

### 4. Codegen (selfhost/codegen/Codegen.hotc)

#### 4.1 Desugar in codegen (or pre-codegen)

**Option A: Desugar during checking** — Convert `Expr.Try` to a `Stmt.Match` + temporary variable during checking. This reuses existing match codegen.

**Option B: Desugar in codegen** — Generate the match bytecode directly in codegen for `Expr.Try`.

**Recommended:** Option B (desugar in codegen) to keep the AST clean and match how the feature is naturally expressed.

#### 4.2 Code generation pattern

For `expr?`:

```
1. Evaluate expr, store in temporary local var_tmp
2. Load var_tmp
3. DUP (duplicate for instanceof check)
4. INSTANCEOF Result$Ok
5. IFEQ label_err (jump if not Ok)
6. CHECKCAST Result$Ok  (safe because we checked)
7. GETFIELD Result$Ok.value_T  (extract the T value)
8. GOTO label_done
9. label_err:
10. DUP (duplicate for instanceof check)
11. INSTANCEOF Result$Err
12. IFEQ label_error_unknown (shouldn't happen)
13. CHECKCAST Result$Err
14. GETFIELD Result$Err.error_E
15. NEW Result$T$E$Err
16. DUP
17. ALOAD_0 (error from stack)
18. INVOKESPECIAL <init>
19. ARETURN (return the Err)
20. label_done:
21. [value T now on stack]
```

Actually, simpler: **Inline the match operation** rather than generating a separate match statement, since we control the exact bytecode.

### 5. Integration Points

#### 5.1 Main.kt (CLI)

No changes needed — `?` is a language feature, not a build mode.

#### 5.2 Type inference

Ensure that when a function's return type is known (e.g., from a declared signature), the `?` operator inside it can use that as context for validation.

## Testing Strategy

### Test 1: Basic unwrap and early return

```hc
fn parse_positive(s: String) -> Result<Int, String> {
    let n = parse_int(s)?;
    if n <= 0 {
        return Result::Err { error: "not positive" };
    }
    return Result::Ok { value: n };
}
```

**Expected:** Compiles, runs, returns Err if parse fails or value is ≤0.

### Test 2: Chained `?` operators

```hc
fn load_texture_safe() -> Result<Texture, String> {
    let path = read_config()?;
    let bytes = read_file(path)?;
    let tex = decode(bytes)?;
    return Result::Ok { value: tex };
}
```

**Expected:** Early return on first error.

### Test 3: Type mismatch error

```hc
fn bad_usage() -> Result<Int, String> {
    let opt = Option::Some { value: 5 };
    let x = opt?;  // Error: Option<T> is not Result<T, E>
    return Result::Ok { value: x };
}
```

**Expected:** Compiler error.

### Test 4: Return type mismatch

```hc
fn bad_error_type() -> Result<Int, Int> {
    let res: Result<Int, String> = ...;
    let x = res?;  // Error: error type String != Int
    return Result::Ok { value: x };
}
```

**Expected:** Compiler error.

### Test 5: Outside Result-returning function

```hc
fn bad_context() -> Int {
    let res: Result<Int, String> = ...;
    let x = res?;  // Error: not in Result-returning function
    return x;
}
```

**Expected:** Compiler error.

## Implementation Checklist

- [ ] Add `Expr.Try { expr: Expr }` to Ast.hotc
- [ ] Update Parser.hotc to recognize `?` postfix operator
- [ ] Add `check_try` method to Checker.hotc
- [ ] Implement Result type checking and extraction logic
- [ ] Track current function's return type during function checking
- [ ] Implement codegen for `Expr.Try`
- [ ] Handle move semantics for Result values
- [ ] Write and pass all test cases above
- [ ] Update README.md with `?` operator documentation
- [ ] Update selfhost files if needed for self-compilation

## Known Limitations / Future Work

1. **No error conversion:** `Result<T, E1>` with `?` in a function returning `Result<T, E2>` where `E1` implements `From<E2>` will still error. This requires trait bounds and is deferred to v2.

2. **No `?` in expressions at file level:** `?` only works inside function bodies, not at module level.

3. **Result must be exactly Result<T, E>:** Couldn't use a custom result-like type.

## References

- Rust's `?` operator: https://doc.rust-lang.org/edition-guide/rust-2015/error-handling/try-operator.html
- Kotlin's `?:` operator (different but related): https://kotlinlang.org/docs/null-safety.html#elvis-operator
- **Ideas.md entry:** See IDEAS.md line 119-155 for detailed rationale
- **Prelude.kt:** Result<T, E> is already defined in the self-hosted prelude
