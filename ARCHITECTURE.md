# Hot Chocolate — Architecture & Design Reference

**This is the deep reference doc** — full design rationale, every
feature's own disclosed scope cuts, real bugs hit and fixed, and the
compiler's own implementation history, section by section. For the
introduction (what this is, why it exists, a taste of the syntax),
start with [README.md](README.md) instead — this doc is what it links
to for the full story once you already know you're interested.

## Status: mid-migration to a self-hosted compiler

**This section is deliberately blunt, because the rest of this doc
was written against an earlier, more complete implementation and has
not been fully caught up yet.** Historically, Hot Chocolate's compiler
was hand-written in Kotlin (`src/main/kotlin/hc/...`). That
implementation has been retired and removed from the repo. The
compiler is now **self-hosted** — written in Hot Chocolate itself, at
`selfhost/{ast,lexer,parser,checker,codegen}/*.hotc` plus
`selfhost/Driver.hotc`, built via a checked-in bootstrap
(`selfhost/bootstrap/*.class`) and run as `SelfhostCLI`. The standard
library — `Option`/`Result`/`Vec`/`Registry`/`read_int`/`read_string`,
every compiled program gets the lot for free by default, or a program
can `use <topic>;` to opt into just what it needs — lives separately at
`stdlib/{option,result,vec,registry,io}.hotc`. See "Status" below for
why it's outside `selfhost/` specifically, and "Standard library
modules" for the full `use` design.

The self-hosted compiler's own source explicitly discloses that it's a
**scoped-down subset**, not a full reimplementation of everything the
Kotlin compiler used to do — e.g. `selfhost/checker/Checker.hotc`
states it is "deliberately NOT attempting move/borrow checking in this
phase," and `selfhost/ast/Ast.hotc` discloses that a struct can
implement at most one interface directly "this phase." Concretely,
right now against `./gradlew run --args="run examples/<file>.hc"`:

- **Works**: `fn main()`/basic functions, `let`/`var`, arithmetic and
  comparisons, `if`/`else`/`while`/`for`, plain structs and field
  access, arrays, `if let`, basic `extern class`/`extern interface`
  Java interop, classpath-based `extern class` signature verification
  (`Checker.hotc`'s `verify_extern_signature`), plain enums and `match`,
  single-interface `&dyn` dispatch, sealed interfaces and
  `match`-by-concrete-type, string interpolation, single-`catch`
  `try`/`catch` and `Result<T, E>`, directory/multi-file compilation
  (`.hc` files, including subdirectory nesting), generic structs and
  generic enums (including bare literals with no surrounding type
  context), and lambda literals targeting a single-method interface.
  `&`/`&mut` borrow syntax parses and runs correctly at the mechanical
  level (passing `&mut p` and mutating through it works).
- **Fixed 2026-09-02**: enums, interfaces, and sealed `match` used to
  fail at runtime (`InstantiationError`, `NoSuchFieldError`,
  `NoSuchMethodError`), and directory-mode compilation used to fail
  entirely (`NoSuchFileException: __init__`). All four turned out to be
  two bugs in `selfhost/Driver.hotc`, not language-design gaps: (1) the
  child-process classpath `run_cli` builds put `selfhost/bootstrap`
  (which contains stale, same-named demo classes from old bootstrap
  builds) ahead of the freshly-compiled program's own output, so the
  wrong class got loaded; (2) directory-mode file collection only ever
  matched the `.hotc` extension (this compiler's own source), never
  plain `.hc` (every real example/project). Both fixed, verified by
  rebuilding the self-hosted compiler with itself and re-running the
  previously-failing examples, and the fix is baked into
  `selfhost/bootstrap/` now. See the "Enums", "Sealed interfaces", and
  "Multi-file projects" sections below for the full story.
- **The headline feature — compile-time move/borrow checking — is now
  fully implemented in `selfhost/`.** Move checking and borrow checking
  (Phases 2 and 3 of the "Compile-time safety roadmap" further down,
  both landed 2026-09-09 — see that section, and the two matching
  Status entries below, for the full design) are both real now: a
  struct passed by value to a function and then used again afterward
  (the exact example "the one genuinely new idea" in FIRST_LANGUAGE.md
  describes) is a real compile error, and so is mutating through a
  plain `&` borrow, or taking `&mut x` when `x` isn't declared `var` —
  see this doc's own "`&mut` vs `&`" section for the enforced rule
  set. This is genuinely the end of the roadmap — Phase 4 is "stop
  there," not a further phase.
- **Fixed 2026-09-02**: `let boxInt = Box { value: 42 };`/`let s = Some
  { value: "hello" };` (a bare generic struct/enum-variant literal with
  no surrounding return-type or "data"-idiom context to infer its type
  argument from) used to fail with `unknown struct or enum variant
  '...'`, and a generic fn taking a generic-struct-typed param
  (`fn unwrap<T>(b: Box<T>) -> T`) used to fail separately with
  "could not infer generic type argument" even once the literal itself
  compiled. Both were real type-inference gaps in `Checker.hotc` and
  `Codegen.hotc` (each redone independently in both files, per their
  own "doesn't share the checker's own registries" split) — fixed by
  inferring the type argument from the literal's own field values when
  no other context is available, mirroring the inference generic
  function calls already had. `examples/generics.hc` and
  `examples/genericenum.hc` now compile and run correctly end to end.
- **Fixed 2026-09-02**: lambda literals (`|params| expr`), previously
  shipped only in the retired Kotlin compiler, are now ported into
  `selfhost/` — a real `Expr.Lambda` AST node, checker inference
  (target-type resolution against a single-method interface, param
  binding), and codegen (a real synthesized implementer class per
  lambda literal, capturing enclosing locals by field, no
  `invokedynamic`). See the "Lambda literals" section below for the
  design and current scope. Verified working for zero-param and
  multi-param lambdas, capturing plain locals and struct fields,
  targeting both `Unit`- and value-returning single-method interfaces,
  as both a top-level fn argument and an extern method/constructor
  argument.
- **Fixed 2026-09-03**: `Float`/`Double` literals used to fail to parse
  at all. The checker/codegen support was already essentially complete
  — only the parser was missing a case for the tokens the lexer already
  produced — but fixing it surfaced three further, genuinely
  pre-existing bugs in array/struct codegen that had simply never been
  exercised by a `Float`/`Double`/`Long` value before (wrong local-load
  opcode and slot width in struct constructors, reference-array
  allocation instead of a real primitive array for array
  literals/`[value; count]`, and the wrong stack-duplication
  instruction for `arr[i] = x` on a category-2 element). See the
  "`Float` / `Double`" section below for details. `examples/floats.hc`
  now compiles and runs with fully correct output end to end.
- **Fixed 2026-09-23**: `123L` used to parse (`Lexer.hotc`'s own `number()`
  already tokenized the `L`/`l` suffix as a distinct `LONG` kind), but
  `Parser.hotc` routed it through `IntLit` + `as Long` — the digits got
  parsed at `Int`'s own 32-bit precision FIRST, so any literal naming a
  value outside `Int`'s range (`9999999999L`) silently truncated before
  the cast ever ran (`examples/long_test.hc`'s own `10000000000L` case
  was hitting exactly this, previously a known-failing example). Fixed
  with a real `LongLit { text }` AST node (mirroring `FloatLit`/
  `DoubleLit`'s own "keep the source text, parse only at the point
  `Codegen.hotc` needs a real LDC constant" deferral) and a new
  `JLongCg::valueOf(String)` extern binding, parsing the full text
  directly at real 64-bit precision. Real `Char` literal syntax
  (`'a'`, `'\n'`, `'\''`) added the same day — a genuinely new lexer
  scanning mode (`char_literal`, single-quoted, one code point,
  resolved at lex time since there's no precision to lose parsing a
  single code point early), reusing `Char`'s already-real int-category
  codegen. `find_matching_brace` (string-interpolation's own brace-
  depth scanner) also learned to skip over a `Char` literal the same
  way it already skipped double-quoted strings, so a `Char` literal
  holding `{`/`}` inside an interpolated expression (`"{describe('{')}"
  `) doesn't confuse the scan. `Byte` still has no literal syntax
  (matches Java's own lack of one — only reachable via `as Byte`), a
  disclosed, intentional scope cut, not an oversight.
- **Added 2026-09-23**: array slicing, `arr[start..end]` (exclusive) /
  `arr[start..=end]` (inclusive), producing a NEW array (a real copy —
  this language's arrays are real JVM arrays with no sub-range-view
  concept). A dedicated `Expr::Slice { arr, start, end, inclusive }` AST
  node rather than promoting `a..b` to a general `Range` expr (`Ast.hc`'s
  own `Stmt::For` header already explains why range stays non-general —
  giving it one would cost a match arm in every OTHER `Expr` site); this
  is the one other place a range-shaped thing is legal, so it gets the
  same narrow treatment. Parsed in `postfix_loop`'s `[`-branch by peeking
  for `DOTDOT`/`DOTDOTEQ` right after the first bracketed expression — no
  lexer change needed, both tokens already existed for `for`-loop ranges.
  Unlike every other feature shipped this session, this one isn't pure
  Parser-level desugaring: `Checker.hotc` gained a real `check_slice`
  (mirrors `check_index`, but returns the array's own type, not the
  element type), and `Codegen.hotc` gained a real new codegen path —
  `java.util.Arrays.copyOfRange`, dispatched to the correct typed
  overload per real primitive array element (`int[]`/`long[]`/`float[]`/
  `double[]`/`byte[]`/`char[]`; `Bool` shares `int[]`'s the same as
  everywhere else in this file) or the generic reference-type overload
  (erased to `Object[]`, needing a `CHECKCAST` back to the real array
  descriptor for the verifier — same mechanism `gen_cast`'s own
  struct/enum branch already uses). Verified against both a primitive
  (`Int`), a `Char`, and a reference (struct array of `Point`) element
  type in `examples/array_slicing.hotc` — the struct-array case exercises
  the `CHECKCAST` path specifically. Not a valid assignment target
  (`arr[a..b] = x` correctly falls through `assignment`'s existing
  wildcard case to "Invalid assignment target" — slicing only ever
  reads).
- **Fixed 2026-09-03**: `is` runtime type checks (`expr is Type`) —
  `is` was lexed as a keyword but never consumed anywhere in the
  parser. Ported as a new `Expr.IsCheck` AST node sharing `as`'s own
  precedence tier and class-name resolution — see the "Runtime type
  checks" section below. Verified against both an extern-class receiver
  and a `&dyn InterfaceName` receiver, chained with `as` in one
  expression.
- **Fixed 2026-09-03**: multiple `catch` clauses on one `try` — see the
  "Error handling" section below for the fix and its verification.
- **Fixed 2026-09-03**: composition delegation sugar (`impl Interface
  for Struct by field`) — see the "Composition delegation sugar"
  section below for the fix and its verification.
- **Fixed 2026-09-03**: a struct implementing more than one interface —
  `struct_interfaces` (checker) and the class's own real JVM
  `implements` list plus its own method table (codegen) all used to
  keep only the FIRST interface/impl-block a struct declared, silently
  dropping the rest; a second `impl` block's own methods never even
  made it into the compiled class, surfacing as a runtime
  `AbstractMethodError`, not a compile error. Fixed to gather from
  every matching `impl` block. See "Same-named methods from unrelated
  interfaces" below for what's still NOT implemented here (no
  completeness checking exists at all yet).
- **Currently broken or unimplemented in `selfhost/`**, despite still
  being documented below as if shipped: annotations (`@serializable`,
  `@entry`, `@dev`), `drop()` (structurally depends on move/borrow
  checking, itself not implemented — see "Status" above). Two-type-parameter generics are
  scoped to enums only (`struct Pair<A, B>` isn't supported — see the
  "Two-type-parameter generics" section below), and bounded generics
  only get instantiation-site validation, not the retired compiler's
  OTHER guarantee of also checking a bounded body once, abstractly (see
  the "Bounded generics" section below). Lambda literals have a
  bare method-reference syntax, single-expression bodies only, and a
  target interface must have exactly one abstract method (ambiguous for
  a multi-method interface) — see the "Lambda literals" section below.
- **Fixed 2026-09-03**: generic type-argument inference through an
  array-typed param (`fn first<T>(arr: &[T]) -> T`) — `check_generic_call`
  only ever matched a bare type-param param or one wrapped in a generic
  struct/enum (`Box<T>`), never one wrapped in `[T]`. Extended both
  independently (checker and codegen don't share this registry, same
  split as everywhere else in this codebase) to also peel one `[...]`
  layer off the argument's own array type. Verified with the same
  generic fn monomorphized at two different type arguments in one
  program (`first(&nums)` → `Int`, `first(&names)` → `String`) via
  `examples/arrays.hc`, which now runs correctly end to end.
- **Fixed 2026-09-03**: `extern class` fields, both static (`Alias::FIELD`)
  and instance (`recv.FIELD`) — `Ast.hotc`'s `ExternClassDecl` had no
  fields list at all, and the parser's `extern class` body loop only ever
  called `extern_method()`, so `static MAX_VALUE: Int;` couldn't even
  parse. Ported the full read AND write path — a real `GETSTATIC`/
  `PUTSTATIC`/`GETFIELD`/`PUTFIELD` per access, the checker/codegen each
  tracking which fields are static (extending the existing "not a struct,
  fall back to the extern-alias table" pattern `check_field_access`/
  `finish_field_access` already used for instance-field lookups). Ahead of
  the retired Kotlin compiler's own scope here, which was read-only. See
  the "`extern class` fields" section below for the syntax and
  verification.
- **Fixed 2026-09-03**: `extends` (subclassing a real Java class,
  `struct S extends C { }`) -- `EXTENDS`/`OVERRIDE` were lexed as
  keywords but never consumed anywhere in the parser. Ported the design
  as built for the retired Kotlin compiler: `S`'s generated classfile now
  has `C`'s real binary name as its actual JVM `superName` (verified with
  `javap`), one real `<init>` per ctor `C` itself declares (each
  forwarding its args straight to `C`'s own `<init>` via
  `INVOKESPECIAL`), and `S::new(args)` resolves against that ctor table.
  An `impl S { override fn method(&self, ...) { ... } }` needs no special
  checker path at all -- it compiles to an ordinary real instance method
  under `C`'s own method name, which the JVM's normal virtual-dispatch
  override rules already pick up correctly once `superName` is right, the
  same way an interface override already worked. Same scope cuts as the
  retired compiler, disclosed in the "`extends`" section below: `S` must
  have zero fields, no `super.method(...)`, single inheritance only
  (`S` itself is emitted `final`). See that section for the full
  verification (`javap` output for a real `LoudList extends
  java.util.ArrayList`).
- **Fixed 2026-09-03**: Java annotations, `@"binary.Name"(arg: value,
  ...)` on a top-level `struct` or `fn` — `@` was lexed as a token
  (`AT`) but never consumed anywhere in the parser. Ported as a real
  classfile `RuntimeVisibleAnnotations` attribute (via new
  `AnnotationVisitor2`/`CodegenShim` ASM bindings, mirroring the
  `ClassWriter2`/`MethodVisitor2` wrappers already used for everything
  else), not a compiler-internal marker — `javap` shows the real
  annotation, arguments included. All three documented argument value
  shapes work: a string literal, `enum("binary.Name", "CONST")`
  (`AnnotationVisitor.visitEnum`), and an array of either
  (`AnnotationVisitor.visitArray`). Storage is a side-channel on
  `Program` (declared name → its one `Annotation`) rather than a field
  threaded through `StructDecl`/`FnDecl` themselves — those two already
  have a dozen-plus constructor call sites across monomorphization,
  delegation-forwarder synthesis, and default-method materialization,
  none of which need to know an annotation feature exists at all. See
  the "Java annotations" section below for the full design and
  verification (`javap`-confirmed output for both a class- and a
  method-level annotation, array argument included).
- **Fixed 2026-09-03**: `open`/`extend` module extension — both were
  lexed keywords (`OPEN`/`EXTEND`) but never referenced anywhere in the
  parser. `pub open interface X { ... }` now parses (a new
  `InterfaceDecl.is_open` field); `extend Target { fn m(&self, ...) ->
  Ret { body } ... }` desugars into an ordinary top-level `FnDecl`
  taking the receiver as an explicit, literally-named `"self"` first
  param — the body's own `self.x` references compile completely
  unchanged, since `"self"` is now just an ordinary local, not the
  special implicit slot-0 receiver an `impl`-block method gets. A
  struct-direct extension's self type is the bare struct name; an
  open-interface extension's is `"dyn Name"`, which is what makes
  `recv.extMethod()` work both directly on a concrete struct AND through
  a `&dyn Interface` reference with zero new dispatch machinery — it
  reuses `is_arg_type_mismatch`'s existing interface-widening check, the
  same one an ordinary `&dyn`-typed extern-method param already relies
  on. `recv.m(args)` resolving to the desugared fn is a last-resort
  checker/codegen fallback, tried only after every real method-lookup
  path has failed. Verified against `examples/modules/` end to end
  (directory-mode, two separate `module`s): a struct-direct extension
  (`Furnace::describe`), an open-interface extension called both
  directly on the concrete struct and through `&dyn Machine`
  (`phoenixTier`, dynamic dispatch), and a negative test confirming
  `extend`ing a non-`open` interface is a real compile error. See the
  "Modules" section below for the full design and verification.
- **Fixed 2026-09-03**: bounded generics, `<T: Trait>` — `<T: ...>`
  used to fail to parse (`unexpected token ':'`) after the type param
  name. A new `FnDecl.type_bounds: Vec<String>` (`+`-joined for
  multiple bounds) is validated at every concrete instantiation
  (`Checker.hotc`'s own `ensure_fn_instantiated`, which now calls a new
  `check_type_param_bounds`): the substituted concrete type must
  genuinely implement every bound trait, checked through the same
  direct-`impl`-plus-transitive-supertrait table `is_arg_type_mismatch`'s
  own `&dyn` widening already reads. Verified both ways: `apply_damage`
  called with a `Player` implementing the bound `Damageable` compiles
  and runs correctly; called with a plain `Int` (which doesn't) is
  rejected with `'Int' for type param 'T' doesn't implement Damageable`,
  right at the call site. **Narrower scope than the retired Kotlin
  compiler's own bounded generics**, disclosed: this port only has that
  one guarantee (validation at each instantiation site) — it's still
  missing the OTHER one the retired compiler eventually added (checking
  a bounded body once, abstractly, against a synthetic merged-bound
  `&dyn` interface, so a body calling a method outside the declared
  bound fails even before any instantiation, not just at an
  incompatible one). See the "Bounded generics" section below for the
  full design and what's and isn't covered.
- **Fixed 2026-09-03**: two-type-parameter generics, scoped to ENUMS —
  `Result<Int, String>` used to fail to parse entirely (this subset's
  generic struct/enum declarations only supported exactly one `<T>`).
  A new `EnumDecl.type_param2: Option<String>` lets `enum Result<T, E>
  { Ok { value: T }, Err { error: E } }` declare a second type param;
  monomorphization now splits a two-arg mono name (`"Result$Int$String"`)
  into its two components independently (`Checker.hotc`'s own
  `ensure_instantiated` and `Codegen.hotc`'s own `monomorphized_enum_
  variants`, redone independently in each per this codebase's usual
  split) rather than assuming everything after the first `$` is one
  argument. **Structs stay single-param this pass** — `Result<T, E>`
  (a sum type where each variant only ever touches ONE of the two
  params) was the actual motivating gap, not a general "N-param
  generics" ask, so `struct Pair<A, B>` isn't supported. Construction
  always needs the fully explicit qualified form
  (`Result<Int, String>::Ok { value: n }`) — bare-literal inference
  (`Ok { value: n }` alone, relying on a surrounding return type) isn't
  attempted for a two-param type, a real, disclosed narrower scope than
  the one-param case's own inference. `Result<T, E>` is now in the
  self-hosted prelude for every compiled program (previously omitted
  specifically because this feature didn't exist yet), and
  `examples/error_handling.hc`'s own `safe_parse` — a real `try`/`catch`
  returning `Result<Int, String>` — now compiles and runs correctly end
  to end, verified together with multi-clause `catch` and `Result`
  pattern-matching in the same program.
- **Fixed 2026-09-03**: `@must_use` — a bare compiler directive (`@`
  followed by a bare `IDENT`, no quoted binary name — distinguished
  from a real `@"binary.Name"(...)` annotation right at parse time by
  whether a `STRING` or an `IDENT` follows the `@`), scoped to
  top-level `fn` only. `Checker.hotc`'s own `check_stmt` rejects a bare
  `Expr.Call` to a `@must_use` fn sitting alone as a statement (nothing
  consuming its return value), and separately rejects `@must_use` on a
  `Unit`-returning fn outright (nothing to enforce). Verified all three
  ways: `let x = compute();` compiles fine, a bare `compute();` is
  rejected with `return value of 'compute' must be used`, and
  `@must_use fn do_thing() { ... }` (`Unit`-returning) is rejected with
  `'@must_use' on 'do_thing' has nothing to enforce -- it returns Unit`.
  Scoped to top-level fns only this pass, same cut the retired Kotlin
  compiler's own v1 made (`impl` block methods aren't covered).
- **Fixed 2026-09-03**: bare method-reference lambdas, `Type::method`
  used directly as a lambda-typed argument instead of the
  `|x| Type::method(x)` wrapper the "Lambda literals" section used to
  say was the only way. Reuses the lambda machinery entirely rather
  than adding a second emission path: the checker/codegen each
  synthesize the equivalent `Lambda { params: [mref_p0, ...], body:
  StaticCall { type_name, method, args } }` (one synthesized param per
  the target interface's own single abstract method param) and hand it
  straight to the existing `check_lambda`/`gen_lambda`, so a real
  resolution failure surfaces through the exact same `check_static_call`
  error path a hand-written call would hit. Works for both `extern
  class` static methods and plain struct static methods, since
  `StaticCall` already resolves both uniformly. The one real wrinkle:
  bare `Type::method` (no parens) was already `Expr.ExternStaticFieldGet`
  (a static field READ, see "`extern class` fields" above) — disambiguated
  purely by whether `type_name` has a real declared field named `method`;
  only a field-shaped miss is treated as a method-reference candidate,
  so `JInteger::MAX_VALUE` used as an ordinary argument still reads the
  real field, unaffected. Verified: a top-level fn call
  (`call_it(JMath::abs, -7)`), an extern instance method's own lambda-typed
  param (`JStream::of(5).map(MathUtil::double_it).sum()`, a struct
  static method reference), and the field-read regression case, all in
  the same session.
- **Fixed 2026-09-03**: structured `///` doc comments — the lexer
  already recognized `///` as a real `DOC_COMMENT` token (`////`,
  four-plus slashes, correctly staying a plain comment), but nothing in
  the parser or checker attached it to anything. Now a real
  `DocComment` node (`summary`, `@param`/`@returns`/`@example`/
  `@warning`/`@see`/`@deprecated` structured sections, an untagged line
  continuing whichever section came before it) that `Parser.hotc`
  attaches to the `struct`/`fn` it immediately precedes, kept as a flat
  side-channel on `Program` (same "don't thread a feature through
  `FnDecl`'s own dozen-plus constructor sites" reasoning `@must_use`/
  annotations already use). `Checker.hotc`'s own new `check_doc_sees`
  runs once, at the very end of checking (once every symbol table is
  fully populated), resolving every `@see` target — both a bare name
  (`SomeStruct`, `some_fn`, an enum, an interface, an extern class) and
  a dotted form (`SomeStruct.field`, `SomeEnum.Variant`) — against the
  real symbol table; an unresolved target is a real compile error, not
  a silent broken link. Verified all three ways: a doc comment with
  valid `@param`/`@returns`/`@see` targets compiles fine, one with a
  `@see NoSuchThing` is rejected with `@see target 'NoSuchThing' ...
  doesn't resolve to a known struct, fn, enum, interface, or extern
  class`, and a `////` divider line is confirmed to NOT attach.
  **Scope cut, disclosed**: no `hc doc` Markdown renderer this pass —
  that's a separate CLI/tooling feature layered on top of this
  compiler-level attachment+validation, not itself a language feature;
  see "Structured doc comments" below.
- **Fixed 2026-09-03**: `hc doc` — the Markdown renderer for structured
  `///` doc comments, the one piece the entry right above explicitly
  didn't build. `SelfhostCLI doc <path> [outFile]` (default `api.md`)
  runs the exact same parse/merge-prelude/`compile_program` pipeline
  `build`/`run` use — a real checker pass, `@see` resolution included —
  then renders `Program`'s own `struct_doc_data`/`fn_doc_data` to
  Markdown: one `###` heading per documented `struct`/`fn`, `@param`s as
  a bullet list, `@warning` as a blockquote, `@see` targets
  backtick-quoted. **Deliberately diverges from the retired Kotlin
  compiler's own design**: that version marked an unresolved `@see`
  `(unresolved)` inline in the rendered output; this one doesn't need
  to, because `check_doc_sees` already rejects a dead `@see` link as a
  real compile error before rendering ever runs — by the time a doc
  renders at all, every `@see` in it is already known-valid. Verified:
  a real doc comment (summary, two `@param`s, `@returns`, `@example`,
  `@warning`, `@deprecated`, `@see`) renders correctly end to end
  (`examples/docs_demo.hc` → `api.md`), a custom output filename works,
  a documented `struct` renders under its own "Structs" heading
  separately from "Functions", and a program with a broken `@see`
  produces no output file at all (the compile error blocks it, same as
  `build`/`run`). One real, unrelated bug found and fixed along the
  way: `java.io.FileWriter`'s no-charset constructor uses the JVM's
  platform-default encoding, which silently mangled a real em dash in
  this renderer's own first verification run on Windows — fixed by
  using the explicit-`Charset` `FileWriter(String, Charset)` overload
  (UTF-8), always.
- **Fixed 2026-09-03**: property-style access, both read AND write —
  `recv.field` falling back to `recv.getField()`/`recv.isField()` when
  no declared field by that name exists, and `recv.field = value`
  falling back to `recv.setField(value)` the same way. **Corrects an
  earlier audit note in this doc** that treated the retired Kotlin
  compiler's read-only version as possibly already carried over into
  `selfhost/` — it hadn't; there was no getter/property-sugar handling
  of any kind before this pass. See "Property-style access" below for
  the full design and verification (both directions, plus the "real
  field always wins" and "genuinely unknown field" cases).

- **Fixed 2026-09-04**: the `?` operator on `Result<T, E>` — `expr?`
  unwraps `Ok { value }` to `value`, or early-`return`s a freshly
  constructed `Result<FnT, E>::Err { error }` for the ENCLOSING fn's
  own `Result` return type (`FnT` may differ per `?` on a chain — only
  `E` has to match). A new `Expr.TryOp` node (named to avoid colliding
  with the pre-existing `Stmt.Try`, since this compiler's own
  variant-name registry is flat across every `enum`, not per-enum
  scoped), parsed as a tight postfix in `Parser.hotc`'s own
  `try_postfix`. `Checker.hotc`'s own `check_try_op` validates the
  operand is genuinely `Result<_, _>`, that the enclosing fn's own
  `self.cur_ret_ty` is too, and that the two `E`s match — rejecting all
  three ways `IMPLEMENTING_TRY_OPERATOR.md`'s own test plan calls out.
  `Codegen.hotc`'s own `gen_try_op` reads `Result`'s real
  `selfhost/`-native representation directly (a single tagged class
  with mangled `Variant$field` fields, like every other enum this
  compiler emits — NOT the separate-subclass-per-variant shape that
  planning doc's own bytecode sketch assumed) and reuses the existing
  `gen_variant_construct` helper to build the early-returned `Err`.
  Verified: a real two-level `?` chain
  (`double_positive` → `parse_positive` → `safe_parse`, mixing a
  `try`/`catch`-derived `Result` with `?` propagation) produces the
  correct `Ok`/`Err` outcome for a valid input, a business-rule
  rejection, and a parse failure, all in one program; `examples/
  try_operator.hc` (previously unusable — written as a forward-looking
  reference before the feature existed, using bare `Result::Ok`/`Err`
  construction that two-type-parameter generics don't support — fixed
  to the qualified form) now compiles and runs correctly end to end.
  See "The `?` operator" section below for the full design.

- **Fixed 2026-09-04**: named arguments — `createEnemy(health: 100,
  damage: 20, name: "Goblin")` — for calls to plain top-level `fn`s.
  A new `Expr.NamedArg { name, value }` node, recognized via a bounded
  1-token lookahead in `Parser.hotc`'s own `one_arg` (`IDENT` directly
  followed by `COLON` — no legal expression production otherwise starts
  that way, so it can't misfire on an ordinary positional arg). Resolved
  back to positional order at the very start of `check_call`/`gen_expr`'s
  own `Call` handling, by looking up the callee's declared param names —
  `Checker.hotc`'s `resolve_named_args_chk` and `Codegen.hotc`'s
  `resolve_named_args_cg`, each against its own `fn_param_names`
  registry, following this compiler's established "checker and codegen
  don't share registries" split. All-or-nothing: mixing named and
  positional in one call is a hard error, and a named argument to
  anything other than a plain top-level `fn` call (a method call, a
  static call, an extern call) is rejected too — both are disclosed
  scope cuts, not oversights. Verified: named args in declaration order,
  named args given out of order (correctly reordered, not just passed
  through positionally — `add(b: 3, a: 10)` on `fn add(a, b) -> a - b`
  correctly returns `7`, not `-7`), a positional call to the same fn
  still working, and both designed error cases (mixed named/positional,
  named args on a method call).

- **Fixed 2026-09-04**: `String.split`/`String.isEmpty` on a native
  `String` receiver — `"a b c".split(" ")`/`s.isEmpty()`. Found doing a
  regression sweep after the named-arguments work above:
  `Checker.hotc`'s own `check_method_call` already trusts ANY method
  call on a native `String` receiver unconditionally (it has no real
  method table for `String`'s built-ins at all — see its own header,
  "resolved for real only in `Codegen.hotc`"), so `Codegen.hotc`'s
  fallback dispatch table for native-`String` methods (`length`,
  `trim`, `substring`, ...) was the ONLY place `split`'s return type
  could ever be determined — and it was simply missing both methods,
  throwing `codegen for String method 'split' not implemented this
  phase` the moment either was called. Added both: `split` emits a real
  `String.split(String) -> String[]` invocation and returns `"[String]"`
  (this compiler's own array-type-name convention, understood
  automatically by the existing `Index`/`.length`-field codegen with no
  changes needed there), `isEmpty` emits `String.isEmpty() -> boolean`
  and returns `Bool`. Verified against `examples/string_bridge.hc`
  (previously unusable) end to end: `"  10 20 30  ".trim().split(" ")`
  iterated correctly (`10`/`20`/`30`), `isEmpty()` correct on both a
  non-empty and an empty string, `substring` unaffected.

- **Fixed 2026-09-04**: a generic call inside a `pub static`'s own
  initializer — `pub static log: Vec<String> = vec_of("boot");` — used
  to crash the compiler itself (`StringIndexOutOfBoundsException` deep
  inside ASM's own stack-map-frame computation), not fail with a clean
  error. Root cause: `register_static_decl` only ever recorded a
  static's DECLARED type, never actually evaluated `.value` against the
  checker at all (its own header said so directly). Skipping that meant
  a `Call` to a generic fn inside a static's initializer never reached
  `ensure_fn_instantiated`, so its monomorphization (`"vec_of$String"`)
  never got requested — `Codegen.hotc`'s `gen_static_init` still emitted
  an `INVOKESTATIC` for it regardless (nothing there re-checks types),
  but with no descriptor ever registered for it, corrupting the emitted
  method descriptor into `""` and crashing ASM instead of failing
  cleanly at compile time. Fixed by actually running `check_expr` on
  every static's initializer, late — after every fn/method signature and
  generic template is registered, in `Codegen.hotc`'s own
  `compile_program` — so a static's initializer requests instantiations
  the same way any other call site does. Also, as a genuine side
  benefit, now catches a mistyped initializer (`pub static x: Int =
  "oops";`) that silently went unchecked before. Verified against
  `examples/statics.hc` (previously unusable) end to end — `bump()`
  mutating a `pub static counter: Int` across three calls, and
  `record()` pushing onto a `pub static log: Vec<String>` initialized
  via `vec_of("boot")` — plus the designed mistyped-initializer error
  case.

- **Fixed 2026-09-04**: `impl<T> Box<T> { ... }` — the leading, Rust-
  familiar spelling for a generic impl block (`impl<T> Labeled for
  Box<T> { ... }` too). `Parser.hotc`'s own `impl_decl` only ever
  accepted the TRAILING form (`impl Box<T> { ... }`, `T` discarded
  outright either way — the checker/codegen key off the struct's own
  already-registered `type_param`, not this token), so the leading form
  failed to parse at all. Fixed by accepting and discarding an optional
  leading `<IDENT>` the same way — a pure parser change, no
  checker/codegen gap once past it.
  - Fixing this surfaced a real, SEPARATE pre-existing bug it made
    newly reachable: a SECOND bare generic-struct literal in one
    program, instantiating `T` differently from the first (`Box { value:
    7 }` then later `Box { value: Player { name: "Kai", hp: 50 } }`),
    failed with `unknown struct or enum variant 'Box'` — with or without
    any `impl` block at all. Root cause: `shape_only_ty`
    (`Checker.hotc`) / `shape_only_ty_cg` (`Codegen.hotc`) — the "guess a
    field literal's type from its own AST shape" fallback backing the
    2026-09-02 bare-generic-literal inference fix — only ever recognized
    `IntLit`/`StringLit`/`BoolLit`/`Ident`, silently returning `"?"` (a
    disclosed narrow scope) for a NESTED `StructLit` or a `FieldAccess`
    like `hero.level`, exactly the two shapes `examples/methods.hc`'s
    second `Box` literal and `examples/showcase.hc`'s `Box { value:
    hero.level }` each needed. Fixed by recognizing both, in both files
    independently (this compiler's usual checker/codegen split):
    `StructLit`'s own `name` field is directly the type name for the
    common non-generic-nested case; `FieldAccess` recurses through
    `shape_only_ty` on `obj` (covering `a.b.c` chains) then looks the
    field up in that struct's own registered field table.
  - Verified end to end against `examples/methods.hc`, `examples/
    interfaces2.hc`, and `examples/showcase.hc` (all three previously
    unusable) — a plain `impl<T>` block, a `impl<T> Interface for
    Struct<T>` block, `Box<Int>` and `Box<Player>` both monomorphized
    correctly in the same program, and a nested-`FieldAccess`-inferred
    `Box<Int>` from `hero.level`, all in one compile.

- **Fixed 2026-09-04**: nullable types, `Type?` — motivated directly by
  Minecraft/Forge modding needs (`UseOnContext.getPlayer()`-shaped APIs).
  Before this pass `Type?` failed to parse anywhere in `selfhost/` at all
  (only the retired Kotlin compiler ever had this); this section of the
  doc had gone stale describing that old version as if it had carried
  over, which it hadn't. New: the `?` type-name suffix (any struct/
  extern-class type, in any type position), a real `null` literal, a
  compile error for using a still-nullable value without a null check,
  and guard-clause narrowing for the two idioms real modding code
  actually needs (`if x == null { <diverges> }`, `if x != null { ... }`)
  — recognized via a raw 3-token PARSER lookahead rather than
  re-inspecting the condition expression downstream (`Expr` is move-only,
  so the checker/codegen genuinely can't peek at it twice). Verified: a
  real `System::getSecurityManager() -> SecurityManager?` extern call
  (`examples/nullable.hc`, previously unusable), both narrowing idioms on
  an HC-native struct with a real method call on the narrowed value, and
  the designed "unnarrowed access" error case. See "Nullable types"
  below for the full design and its disclosed scope cuts (native
  `String?` chief among them — not covered this pass).

- **Fixed 2026-09-04**: `?.` (safe navigation) and `?:` (Elvis), layered
  directly on top of `Type?` above — `x?.method()`/`x?.field` short-
  circuit to null without evaluating args when the receiver is null;
  `x ?: default` reduces the `if x == null { ... }` guard clause to one
  expression. Genuinely resolved a real grammar conflict along the way:
  `?` alone is already the `?` (try) operator's own postfix trigger, so
  `x ?: y`/`x?.y` needed careful lookahead (`?.` consumed inside the
  ordinary postfix chain before the try-postfix loop even runs; `?:`
  needs an explicit "not followed by `:`" guard in that loop) to avoid
  misparsing as `TryOp` plus a dangling `: y`/`.y`. Also found and fixed
  a real crash running this for real: `?.`'s own codegen originally left
  the null path's value typed as the RECEIVER's own class while the
  non-null path leaves the member's (different) result type, forcing
  ASM's `COMPUTE_FRAMES` to reflectively resolve a struct still being
  compiled in the same pass (`ClassNotFoundException`) — fixed by
  pushing an untyped `ACONST_NULL` on that path instead. Verified end to
  end via `examples/safe_nav.hc` (new), including both designed error
  cases. See "The `?.`/`?:` operators" below for the full design.

- **Fixed 2026-09-04**: native `String?` — the last disclosed nullable-
  types gap. `+`, string interpolation, and `==`/`!=` all had their own
  exact-match `"String"` special cases that a `"String?"`-suffixed name
  never matched; `+`/interpolation now give a clear "possibly-null String
  used without a null check" error instead of falling through to a
  confusing unrelated one, and `==`/`!=` now correctly routes anything
  that isn't a PROVABLY non-null `String` through `Objects.equals`
  instead of a raw `.equals()` call. That last one closed a real,
  newly-reachable bug: `null == "literal"` used to call
  `INVOKEVIRTUAL Object.equals` with `null` as the receiver —
  `null.equals(...)`, a guaranteed `NullPointerException`, not a compile
  error — latent since before this whole pass but only reachable once
  `null` became a real expression. Guard-clause narrowing needed NO
  changes at all (it never special-cased what kind of nullable value it
  was narrowing to begin with). Verified end to end via `examples/
  nullable.hc` (extended): a real `System::getProperty` call narrowed and
  used with `+`/`.trim()`, `?.`/`?:` on a native `String?`, a null-safe
  `==` against both `null` and a literal on an unnarrowed value
  (confirming no NPE), and both designed error cases. See "Native
  `String?`" below for the full design.

- **Fixed 2026-09-08**: numeric widening — a mixed `Int`/`Long`/`Float`/
  `Double` pair in arithmetic or a comparison now widens to the "bigger"
  side (`5 * 2.0` -> `10.0`) instead of requiring an exact type match.
  Motivated directly by ordinary game-code arithmetic (a bare `Int` loop
  counter or literal mixed into `Double`/`Float` math), where the old
  exact-match rule meant an explicit `as Double` on every such mix — real,
  constant friction. `Int`+`Long` together deliberately still isn't
  widened (would need real sign-extension bytecode, not just "convert
  the smaller float-family type up," and no real caller needed it this
  round) — mixing those two is still a compile error, unchanged.
  Codegen spills both operands through two scratch local slots to
  convert each one while it's alone on top of the stack, since the JVM
  has no in-place conversion for a value sitting below the top (and a
  category-2 `Long`/`Double` makes the usual `DUP`/`SWAP` tricks
  genuinely awkward for every width combination). Verified across every
  numeric-type combination, both operand orders, all five arithmetic
  ops, and all four comparisons, via `examples/floats.hc`'s own extended
  coverage. See "Numeric widening" below for the full design.

- **Fixed 2026-09-08**: operator overloading — a plain struct's own
  `impl` method, named per a fixed convention (`add`/`sub`/`mul`/`div`/
  `rem`/`eq`/`neg`, mirroring Rust's own operator-trait method names —
  NOT a real trait/interface declaration), lets `+`/`-`/`*`/`/`/`%`/
  `==`/`!=`/unary `-` dispatch to it instead of requiring
  `Int`/`Long`/`Float`/`Double`. Motivated directly by the math-heavy
  types a game engine leans on (`Vec2`/`Vec3`/`Mat4`-style structs),
  where every op otherwise needs a named method call (`a.add(b)`) —
  real, constant friction for math-heavy code. The right-hand side
  doesn't have to be the same struct type — the method's OWN declared
  param type decides (`Vec2 * Double` works by declaring `fn mul(&self,
  scalar: Double) -> Vec2`, no separate "scalar multiply" mechanism
  needed). Codegen never re-evaluates either operand: both are already
  on the stack in exactly the order `INVOKEVIRTUAL` wants (receiver,
  then the sole argument) by the time the check runs, so it's a direct
  call, not a synthesized one. Deliberately does NOT cover `<`/`<=`/
  `>`/`>=` — full ordering would need a struct to implement all four (or
  a single `cmp`-style method returning something like `Ordering`), a
  bigger design than the real motivating cases (`Vec2` arithmetic and
  value equality) actually needed. Verified end to end via the new
  `examples/operators.hc`: `+`/`-`/`*` (including a scalar right-hand
  side), unary `-`, `==`/`!=` (real value equality, not reference
  identity), and the designed "no such method, falls through to the
  ordinary numeric error" case. See "Operator overloading" below for
  the full design.

- **Fixed 2026-09-08**: the standard library separated from the
  compiler, in two steps taken the same day. First, out of the
  compiler's own source and into real files — `Option<T>`/
  `Result<T, E>`/`Vec<T>`/`Registry<T>`/`read_int`/`read_string` (every
  compiled program's implicit prelude) used to live as `Driver.hotc`'s
  own `fn prelude_source() -> String`, ~125 lines of escaped-brace
  string concatenation (this language's own string literals have no
  escape-sequence support, so every `{`/`}` had to be written `\{`/
  `\}`, joined line by line with `+ nl +`) — unreadable, undiffable,
  real friction every time the standard library itself needed a fix.
  Second, split that one bundle into five independent topic files
  (`stdlib/{option,result,vec,registry,io}.hotc`) and given a real
  `use <topic>;` statement so a program can opt into just the topics it
  actually needs, instead of always getting everything. `stdlib/` lives
  OUTSIDE `selfhost/` deliberately — `selfhost/`'s own directory-mode
  self-compile (`./gradlew run --args="run selfhost"`) would otherwise
  sweep it up a SECOND time as just another loose `.hotc` tree,
  double-declaring everything in it on top of the explicit stdlib-merge.
  **100% backward compatible**: a program with NO `use` line anywhere
  (every example that predates this feature) still gets the full legacy
  bundle, unchanged — `use` is additive, not a breaking requirement.
  Verified: self-hosting still succeeds end to end; every pre-existing
  example using `Vec`/`Registry`/`Option`/`Result` still compiles and
  runs correctly with byte-for-byte the same output as before either
  move; the new `examples/stdlib_use.hc` exercises real opt-in
  (`use registry;` alone transitively pulling in `vec`+`option`,
  `Result` genuinely NOT available without naming `result`); and the
  designed "reference a topic that was never `use`d" and "`use` an
  unknown topic name" error cases both fail cleanly. See "Standard
  library modules" below for the full design.

- **Fixed 2026-09-08**: Phoenix Flight — a real background task pool
  (`stdlib/phoenix.hotc`, opt-in via `use phoenix;`) wrapping
  `java.util.concurrent`, with a REAL compile-time safety check: a
  `pool.spawn(|| ...)` lambda can only capture `Copy` primitives,
  `String`, or a struct explicitly marked `@sendable` (a new bare
  directive, mirroring `@must_use`) — capturing a plain mutable struct
  into a background task is a compile error, not a data race waiting to
  happen at runtime. Building this surfaced and fixed two real, more
  general infrastructure gaps along the way, both independently
  valuable: (1) a lambda literal passed to a PLAIN (non-`extern`)
  struct method's own `&dyn Interface` param never had its target type
  inferred at all — `pool.spawn(|| ...)`-shaped code (a method call, not
  an extern call) threw "lambda literal used outside a valid
  call-argument context" even though that's exactly what a `&dyn
  Interface` param is for; (2) `Object`-erased generic JDK types
  (`Future<T>`/`Callable<T>`) need their extern bindings marked as real
  interfaces (`extern class ... interface`) or dispatch fails at RUNTIME
  with `IncompatibleClassChangeError`, not at compile time — a real trap
  now documented for the next binding that hits it. Verified end to end
  via the new `examples/phoenix_flight.hc`: a real background thread
  computing a result and joining it back on the main thread, a
  `@sendable`-marked struct's capture correctly allowed, and the
  designed "capture a non-`@sendable` struct" error case correctly
  rejected. See "Phoenix Flight" and "Compile-time concurrency safety"
  below for the full design and disclosed scope cuts (no dependency-
  graph/`spawn_after` chaining, no unboxed primitive task results, no
  general ECS/ownership-derived scheduler — see `IDEAS.md`'s own "ECS
  with ownership-derived system scheduling" entry for that much bigger,
  separate design that presupposes a real move/borrow checker first).

- **Fixed 2026-09-08**: real lexical scoping — Phase 1 of a small,
  disclosed-scope roadmap toward the headline move/borrow-checking
  feature above (see "Compile-time safety roadmap: lexical scoping,
  move checking, borrows" below for the full 4-phase plan and why it
  deliberately stops well short of Rust's own lifetime
  parameters/elision/variance). `Checker.hotc`'s own `self.vars` used to
  be one flat, whole-function table — a `let x` inside an `if`/`while`
  body silently overwrote the SAME slot for the rest of the function,
  rather than being scoped to just that block, and a `let x: String`
  inside a nested block would permanently "retype" an outer `x: Int` for
  every read after the block too. Real fix: `TyChecker.var_scopes` (a
  stack of enclosing scopes) plus `push_scope`/`pop_scope`/`lookup_var`,
  pushed by `check_block` around every `{ ... }` and by match
  arms/`for`-loop vars/`catch` clauses around their own bindings.
  `Codegen.hotc` needed the exact same treatment independently (it keeps
  its own separate `var_types`/`slots` local-variable bookkeeping, not
  shared with the checker) — `CodeGen.var_type_scopes`/`slot_scopes` plus
  `push_var_scope`/`pop_var_scope`/`lookup_type`/`lookup_slot`, wired
  into `gen_block` and the same match/`for`/`catch` sites, so a shadowed
  local's JVM slot correctly reverts to the outer one once its block
  ends instead of staying stuck on the inner block's slot/type. Verified
  with a real shadowing test (`{ let x: Int = 1; { let x: String = "hi";
  print(x); } print(x); }`, printing `hi` then `1`, not `hi` twice) and a
  full self-hosting rebuild + regression sweep.

- **Fixed 2026-09-09**: real move checking — Phase 2 of the same
  roadmap (see "Compile-time safety roadmap" below, now updated with
  Phase 2's full design and disclosed cuts). Passing a struct/enum by
  value now genuinely moves it — `let y = x;`, `return x;`, or calling a
  fn/method whose param has no leading `&`/`&mut` — and using the OLD
  variable again afterward (a direct read, a field access, a method
  call, anything that resolves through `check_ident`) is a real compile
  error, `"use of moved value 'x'"`, not silently accepted the way it
  always has been until now. A real prerequisite gap turned up building
  this: `&`/`&mut` used to be pure syntax — parsed and immediately
  discarded, with NO record anywhere of which params were declared
  by-reference (`Parser.hotc`'s own header said as much: "this subset
  has no borrow-checking at all either way"). Fixed first, underneath
  this: `Ast.hc`'s `Param` gained a real `is_ref: Bool` field, and
  `Checker.hotc` gained `fn_param_is_ref`/`method_param_is_ref`/`static_
  method_param_is_ref` tables so a call site can ask "is this specific
  argument position a borrow?" — a by-ref param never moves its
  argument; a plain by-value one does. `Int`/`Bool`/`Long`/`Float`/
  `Double`/`String` are never moved (same primitives-are-Copy set
  `@sendable` already uses) — only structs/enums are.
  - **`if`/`else` branch merging**: only one branch of an `if` actually
    runs, so moving `x` in just the `then` branch (with no `else`, or an
    `else` that doesn't touch `x`) still makes `x` "maybe moved"
    afterward — the checker conservatively treats it as moved either
    way, `TyChecker.moved`'s own real scope-stack (parallel to `var_
    scopes`, so a move recorded on an OUTER local from inside a nested
    block correctly persists past that block ending) makes this
    tractable to check without false accepts. A branch that
    unconditionally `return`s/`throw`s is excluded from the merge — see
    `stmt_list_diverges_chk`'s own header — the single most common
    pattern this needed to get right (`if special_case { ...moves x...;
    return y; } return x;`, an extremely ordinary guard-clause idiom),
    without which nearly every `if`-heavy fn in this very compiler's own
    source would have failed to self-host.
  - **Disclosed scope cuts**: a struct-literal field VALUE (`Foo { field:
    y }`) does NOT move `y` — sharing one already-built value (a `Vec`,
    say) across several sibling struct literals is common and
    completely safe under this language's actual reference semantics,
    and real construction code leans on it constantly; treating it as a
    move caused far more friction than value, so it was cut. A method
    call's RECEIVER is never treated as moved (`self`'s own `&`/`&mut`/
    bare-value distinction still isn't tracked at all — a real, further
    gap, not just this feature's). Extern-method and generic fn/method
    calls are exempted entirely (their `is_ref` data is simply never
    registered, so every argument there is trusted as a borrow) —
    narrower than the plain-fn/plain-method coverage above, but avoids
    rejecting code this pass has no way to check precisely yet. `match`
    arms and `try`/`catch` clauses don't get the same two-branch merge
    `if`/`else` does — a variable moved in one arm can spuriously read
    as "already moved" while checking a LATER, unrelated arm, a real,
    over-strict (never unsound) false-positive risk. Moving a variable
    captured from an ENCLOSING scope inside a `while`/`for` loop body
    isn't specially flagged either, even though the loop could run more
    than once — genuinely unsound, and simply not caught this phase.
  - **Verified**: a real compile error for the textbook case (`let a =
    Player { hp: 10 }; consume(a); print("{a.hp}");` — `"use of moved
    value 'a'"`), a matching success case exercising every mechanism at
    once (`&`-borrowing not moving, reassignment un-moving, the
    if/`else` merge, and a diverging branch correctly excluded from it —
    see `examples/move_checking.hc`), a full self-hosting rebuild, and a
    full regression sweep. Self-hosting itself was the real stress test
    here: turning this on exposed ~40 genuine "reads its own argument
    more than once across sibling `if`-branches" patterns across the
    compiler's OWN ~15k lines of source — nearly all fixed the same way,
    by marking a purely-read-only helper's own `Expr`/`Stmt`/`Vec<...>`
    param `&` (`check_expr`/`gen_expr` themselves, being called
    everywhere, accounted for most of it) — a small number of genuine
    "this really does need to move a value more than once" cases in
    older bootstrap regression fixtures (`selfhost/demo_program*.hcsrc`)
    were fixed by marking THEIR OWN accessor fns' params `&` too, the
    same real fix a user hitting this would make.

- **Fixed 2026-09-09**: real borrow checking — Phase 3 of the same
  roadmap, and the last phase this project actually plans to build (see
  "Compile-time safety roadmap" below, and this doc's own "`&mut` vs
  `&`" section for the full rule set). `&`/`&mut` used to be pure
  syntax parsed and immediately discarded — `Ast.hc`'s `Param` gained
  the shared-vs-exclusive distinction Phase 2 deliberately left out
  (`is_mut_ref: Bool`, alongside its own `is_ref`), and `FnDecl` gained
  `self_is_mut: Bool` (`self`'s own ref-kind wasn't tracked AT ALL
  before this — a method's receiver was parsed and discarded exactly
  like every other `&`/`&mut` before Phase 2 started). Four real rules,
  all enforced now: (1) `obj.field = value` requires `obj` to be a
  `var` local, an `&mut`-declared param, or `self` in an `&mut self`
  method — assigning through a `let` local or a plain `&` param is a
  real compile error; (2) passing a bare local to an `&mut` param
  requires that local to itself be mutably accessible (the exact same
  property rule 1 checks, reused); (3) within one call's ARGUMENT list,
  the same variable can't be borrowed `&mut` alongside any other borrow
  of it (`foo(&mut p, &p)` rejected, `foo(&p, &p)` fine); (4) a struct
  field can never be declared a reference type at all (`struct S { x:
  &Int }` — previously silently accepted, since nothing checked
  `Param.is_ref` on a struct's own fields).
  - **Disclosed scope cut**: rule 3's exclusivity check covers the
    argument list only, not the implicit receiver of a method call
    (`p.foo(p)`, where `foo`'s own receiver is `&mut self` and `p` is
    ALSO passed as an argument) — doing that precisely needs the
    resolved method's own `self_is_mut` threaded all the way through
    `check_method_call`'s own dispatch, which doesn't currently carry
    it that far. A real, narrower gap than the full rule, not a
    soundness hole in what IS checked.
  - **Verified**: five designed-error cases, one per rule above plus
    the plain-`&self`-can't-mutate case, each producing the exact
    intended compile error; a matching success case exercising `&mut`/
    `&` together correctly (see `examples/borrow_checking.hc`); a full
    self-hosting rebuild; and a full regression sweep across every
    existing example — all 35 passed completely unchanged, meaning
    every example this project had already written happened to follow
    real borrow discipline already, even before anything enforced it.
    Self-hosting itself needed exactly one fix beyond the AST plumbing:
    the same "reentrant `ensure_fn_instantiated`/`ensure_method_
    instantiated` calls need to save/restore ALL of the checker's
    per-scope state, not just some of it" bug Phase 2 already hit once
    for `moved`/`moved_scopes` — this phase's own new `mut_capable`/
    `mut_capable_scopes` needed the identical fix.

Treat every feature section below as "the language as designed," not
as a guarantee it currently compiles under `selfhost/` — where a
feature is known-broken today, its section says so.

### `Float` / `Double`

**Fixed 2026-09-03**: `Float`/`Double` literals used to fail to parse
(`unexpected token '3.5'`). Turned out to be a narrow, isolated gap —
`Checker.hotc`/`Codegen.hotc` already had full `Float`/`Double` support
throughout (arithmetic, casts, comparisons, descriptors, `TyFloat`/
`TyDouble` as real `Ty` variants) from when this section was first
built; only the PARSER never grew a case for the `FLOAT`/`DOUBLE`
tokens the lexer already produced. Fixing that surfaced three further,
genuinely pre-existing bugs that had just never been exercised before
(no struct/array ever held a `Float`/`Double`/`Long` value until now):
a struct constructor loading a field value with the wrong opcode
(`ILOAD` instead of `FLOAD`/`DLOAD`, and not widening the next field's
local slot by 2 for one), array literals/`[value; count]` allocating a
reference array instead of a real primitive `float[]`/`double[]` via
`NEWARRAY`, and `arr[i] = x` for a `Long`/`Double` element using the
wrong stack-duplication instruction (`DUP_X2` instead of `DUP2_X2`).
All fixed; `examples/floats.hc` (arithmetic, struct fields, extern
methods, comparisons, `while`/`for` loops, arrays, and `Vec<Double>`
from the generic prelude) now compiles and runs with fully correct
output end to end.

Two more `Copy` primitives alongside `Int`/`Bool`/`String`, added
specifically to unblock real Minecraft interop — `BlockPos` math is
`Int`, but rendering/camera/entity-position code is `double`-heavy, and
render-side APIs mix in plain `float` too.

```
extern class JMath = "java.lang.Math" {
    fn sqrt(x: Double) -> Double;
}

fn main() {
    let a: Float = 3.5f;   // 'f' suffix -> Float, matching Java's own literal convention
    let b = 3.5;           // unsuffixed -> Double, also matching Java's default
    print(JMath::sqrt(9.0));
}
```

- `1.5` is a `Double` literal (Java's own unsuffixed default); `1.5f`/
  `1.5F` is a `Float` literal — deliberately mirroring Java's suffix
  convention, since the whole point is recognizing these against real
  Java signatures.
- **Corrected 2026-09-08**: this bullet used to say arithmetic/comparison
  required an EXACT type match with no implicit promotion at all — real
  friction writing ordinary game-code math (`radius * 2`, a bare `Int`
  loop counter mixed into `Double` math), fixed by real numeric
  widening. See "Numeric widening" below for the full story; `1 + 1.0`
  now widens to `2.0` rather than erroring. `==`/`!=` between mismatched
  numeric types is unchanged (still not validated either way — a
  separate, pre-existing gap this pass didn't touch).
- `Double` is a genuinely *wide* JVM value (two local-variable-table/
  operand-stack slots, like `long`) — every slot-allocating or stack-
  duplicating codegen path (`declareLocal`/`declareParam`, `DUP` vs
  `DUP2`) accounts for this; `Float` is a normal one-slot value.
- `<`/`<=`/`>`/`>=` compile to the same NaN-safe `FCMPG`/`FCMPL`
  convention `javac` itself uses (`CMPG` for `<`/`<=`, `CMPL` for
  `>`/`>=`), so a comparison against `NaN` always comes out `false`,
  matching IEEE 754, rather than an arbitrary choice.
- Works everywhere `Int` does: struct fields, arrays, `print()`, and
  transparently through the generic `Vec<T>`/`Registry<T>` prelude
  (`Vec<Double>` needed no changes — the wide-slot handling above is
  what makes that "just work"). See `examples/floats.hc`.

### Numeric widening

**Fixed 2026-09-08** — see "Status" near the top of this doc. A mixed
numeric pair in arithmetic (`+`/`-`/`*`/`/`/`%`) or a comparison
(`<`/`<=`/`>`/`>=`) widens to the "bigger" side instead of requiring an
exact type match:

```
print(5 * 2.0);      // 10.0 -- Int widened to Double, either operand order
print(count * dt);   // count: Int, dt: Double -- no `as Double` needed
var total = 0.0;
var i = 0;
while i < 3 {
    total = total + i * 1.5; // an Int loop counter mixed into Double math
    i = i + 1;
}
```

- **The rule** (`Checker.hotc`'s `numeric_widen_result` / `Codegen.hotc`'s
  identical `_cg` twin): `Int`/`Long`/`Float` widen up to `Double` when
  either side is `Double`; `Int`/`Long` widen up to `Float` when either
  side is `Float`. **`Int`+`Long` together is deliberately still NOT
  widened** — a real, disclosed scope cut: that pair would need genuine
  sign-extension bytecode (`I2L`), a different shape than "convert the
  smaller float-family type up," and no real caller needed it this
  round. Mixing `Int`+`Long` is still a compile error naming both types,
  unchanged from before this pass.
- **Codegen**: the JVM has no opcode to convert a value sitting BELOW
  the top of the stack in place, and a category-2 value (`Long`/`Double`,
  two stack words) makes the usual `DUP`/`SWAP` stack-reordering tricks
  genuinely awkward to get right for every width combination. Solved by
  spilling both operands through two scratch local slots instead (the
  same "grab `self.next_slot` directly, no name registration" pattern
  already used elsewhere in this file for similar width-mismatch
  problems) — convert each operand immediately after it's alone back on
  top of the stack, where a plain conversion opcode (`I2D`/`I2F`/`L2D`/
  `L2F`/`F2D`) is always enough. A same-type pair (still the overwhelming
  common case) returns immediately with no extra bytecode at all.
- **Scoped to arithmetic and the four ordering comparisons only** —
  `==`/`!=` between mismatched numeric types is a separate, pre-existing
  gap (never validated by the checker at all, in either direction) this
  pass didn't touch; widening would also emit real conversion bytecode,
  which a reference-identity/enum-tag `==` comparison never wanted in
  the first place.
- Verified: every combination (`Int`/`Long`/`Float` each paired with
  `Double`, `Int`/`Long` paired with `Float`, both operand orders, all
  five arithmetic ops, all four comparisons, inside a `while` loop) via
  `examples/floats.hc`'s own extended coverage, plus the designed
  `Int`+`Long` rejection case still correctly erroring.

**Known limitation**: `arena struct` fields are still `Int`/`Bool` only
— the off-heap layout math assumes a uniform 4-byte field size, and
`Double` (8 bytes) doesn't fit that without a real packing rework.

### Operator overloading

**Fixed 2026-09-08** — see "Status" near the top of this doc. Motivated
directly by math-heavy game-engine types (`Vec2`/`Vec3`/`Mat4`-style
structs), where every arithmetic op otherwise needs a named method call:

```
struct Vec2 { x: Double, y: Double }

impl Vec2 {
    fn add(&self, other: Vec2) -> Vec2 {
        return Vec2 { x: self.x + other.x, y: self.y + other.y };
    }
    fn mul(&self, scalar: Double) -> Vec2 {
        return Vec2 { x: self.x * scalar, y: self.y * scalar };
    }
    fn eq(&self, other: Vec2) -> Bool {
        return self.x == other.x && self.y == other.y;
    }
    fn neg(&self) -> Vec2 {
        return Vec2 { x: -self.x, y: -self.y };
    }
}

let a = Vec2 { x: 1.0, y: 2.0 };
let b = Vec2 { x: 3.0, y: 4.0 };
print((a + b).x);      // 4.0 -- dispatches to `add`
print((a * 2.0).x);    // 2.0 -- `mul`'s own param decides the right-hand type
print(a == Vec2 { x: 1.0, y: 2.0 }); // true -- real value equality, via `eq`
```

- **A fixed naming convention, not a real trait/interface** — a plain
  `impl StructName { fn add(&self, other: T) -> R { ... } }` is all
  that's needed; there's no `operator fn`/`impl Add for Vec2` syntax and
  no interface declaration to satisfy. `Checker.hotc`'s own
  `check_binary`/`check_unary` just look up `StructName#<name>` in the
  ordinary method table (`self.methods`, the SAME registry any plain
  method call already resolves against) before falling through to the
  built-in `Int`/`Long`/`Float`/`Double` handling — mirrors Rust's own
  operator-trait method names (`Add::add`, `Sub::sub`, `Mul::mul`,
  `Div::div`, `Rem::rem`, `PartialEq::eq`, `Neg::neg`) purely as a
  discoverable naming choice, not an implementation of Rust's trait
  system.
- **The mapping**: `+`→`add`, `-`→`sub`, `*`→`mul`, `/`→`div`, `%`→`rem`,
  `==`/`!=`→`eq` (`!=` negates `eq`'s own `Bool` result — codegen emits
  one `IXOR` after the call, no second method needed), unary `-`→`neg`.
  Every binary one takes exactly 1 parameter (checked — a wrong arity is
  a real error naming the method and struct); `neg` takes none.
- **The right-hand side isn't required to be the same struct type** —
  whatever `add`/`mul`/etc. actually declares for its own single param
  is what gets checked, the exact same arg-type validation (including
  widening) an ordinary method call already gets. This is what makes
  `Vec2 * Double` (a scalar multiply) work with no separate mechanism:
  `mul`'s own declared param is just `Double`.
- **Codegen never double-evaluates either operand** — by the time
  `gen_binary`'s own per-op branch checks for an operator-overload match,
  `left`'s value (the receiver) and `right`'s value (the sole argument)
  are ALREADY on the stack, pushed by that branch's own two `gen_expr`
  calls, in exactly the order a real `INVOKEVIRTUAL` wants
  (`[objectref, arg]`) — so a match is a direct call against the
  already-registered method descriptor, never a re-synthesized one that
  would evaluate `right` a second time.
- **Deliberately does NOT cover `<`/`<=`/`>`/`>=`** — full ordering
  would need a struct to implement all four separately, or a single
  `cmp`-style method returning something like `Ordering` (which this
  language has no such type for), a bigger design than the real
  motivating cases (`Vec2`-style arithmetic and value equality) actually
  needed this round. Comparing two structs with `<` etc. is still a
  compile error, unchanged from before this pass.
- Verified end to end via `examples/operators.hc`: `+`/`-`/`*` (including
  a `Vec2 * Double` scalar right-hand side), unary `-`, `==`/`!=` (real
  value equality, not reference identity — two separately-constructed
  `Vec2`s with equal fields compare equal), and the designed "no such
  operator method — falls through to the ordinary numeric error, not a
  crash" case.

### Standard library modules

**Fixed 2026-09-08** — see "Status" near the top of this doc. The
standard library — `Option<T>`, `Result<T, E>`, `Vec<T>`, `Registry<T>`
(plus `vec_of`/`registry_new`), and `read_int`/`read_string` — lives at
`stdlib/{option,result,vec,registry,io}.hotc`, one real `.hotc` file per
topic, and a program can opt into just the topics it actually needs.
Phoenix Flight (`stdlib/phoenix.hotc`, a background task pool — see its
own section below) is a sixth topic with a real difference: it's opt-in
ONLY, never part of the "no `use` at all" default bundle the original
five still form.

```
use registry;   // pulls in `vec` and `option` too -- see the dependency list below

fn main() {
    var r = registry_new("rin", 100);
    r.register("kai", 50);
    let found = r.get("rin"); // Option<Int>::Some { value: 100 }
}
```

- **No `use` line anywhere in the whole program → the full legacy
  bundle, unchanged** — every one of the many pre-existing examples
  built on `Vec`/`Registry`/`Option`/`Result` (none of which could have
  written a `use` statement, since it didn't exist yet) keeps compiling
  and running exactly as before, with zero changes needed. This is the
  actual backward-compatibility mechanism, not a special case bolted on
  top of it: `Program.uses` (`Ast.hc`'s own header) is just the flat list
  of every `use topic;` line found anywhere in the merged program, and
  `Driver.hotc`'s own `resolve_stdlib_modules` treats an EMPTY list as
  "inject everything" — the exact behavior every file already relied on
  before `use` was invented.
- **One or more `use topic;` lines → real opt-in**, only those topics
  (plus their own transitive dependencies) get merged in. Referencing a
  type/fn from a topic that was never named is a real error (`unknown
  struct or enum variant 'Some'` for `Option`, say) — not silently
  available anyway, and not a special-cased warning either.
- **The topics and their real dependencies**: `option` and `result`
  have none; `vec` has none; `registry` depends on `vec` (its own
  storage) and `option` (`get`'s own return type); `io`
  (`read_int`/`read_string`) depends on `option` (`read_int`'s own
  return type); `phoenix` (Phoenix Flight, see its own section below)
  has none; `phoenix_virtual` depends on `phoenix` (reuses its
  `PhoenixTask`/`PhoenixCallable` directly); `ecs` depends on `vec` and
  `phoenix` (codegen-internal usage, independent of what the user's own
  source writes -- see `Driver.hotc`'s own `add_stdlib_topic_and_deps`);
  **`math`** (`stdlib/math.hotc`, added 2026-09-10 -- `Vec2`/`Vec3`/
  `Vec4`/`Mat4`/`Quaternion` plus a seamless `extern class` wrapper over
  `java.lang.Math`'s own scalar functions) has none -- entirely
  self-contained, no `Vec<T>`/`Option<T>` usage anywhere in it. See its
  own file header for the one real, disclosed scope cut this makes:
  `java.lang.Math`'s `abs`/`min`/`max`/`round` are each overloaded for
  `int`/`long`/`float`/`double` in real Java, but this compiler's own
  extern method registry keys by `"Alias#name#arity"`, not by parameter
  type, so only the `Double` overload of each binds under `Math::`; the
  `Int`/`Float`/`Long` siblings (`abs_int`/`min_int`/`max_int`/
  `abs_float`/`min_float`/`max_float`/`abs_long`/`min_long`/`max_long`)
  are ordinary hand-written HC functions instead, trivial enough that
  no `java.lang.Math` call was needed for them at all -- covering every
  one of `abs`/`min`/`max`'s own four real Java overloads under some
  name. `Float` matters here beyond completeness: `Vec2`/`Vec3`/`Vec4`/
  `Mat4`/`Quaternion` are all `Float`-based, so without `abs_float`/
  `min_float`/`max_float`, clamping/comparing their own components
  would otherwise need a round-trip cast out to `Double` and back.
  Verified end to end (cross product, matrix identity/translation/
  rotation, quaternion-to-matrix, and `slerp` all cross-checked against
  each other and against hand-computed expected values) via
  `examples/math_demo.hc`. **`window`** (`stdlib/window.hotc`, added
  2026-09-11 -- real GLFW windowing/input plus minimal OpenGL
  clearing/presenting, via LWJGL) also has none, but is different in
  kind from every other topic here: it's the first to bind against a
  real THIRD-PARTY library, not a JDK class -- nothing in
  `HotChocolate`'s own repo depends on LWJGL at all (it's a real
  external Maven dependency the CONSUMING project, e.g. Marshmallow,
  supplies on its own classpath), so `class_exists_reflect` finds
  nothing for it with an empty `--classpath` and real `--classpath`
  signature verification only ever engages once a consuming project's
  own `compileClasspath` (auto-threaded by the `hc` Gradle plugin)
  actually includes LWJGL. `glfwCreateWindow`'s own `title` param is
  bound against its REAL declared type (`java.lang.CharSequence`, not
  `String`) via a dedicated extern alias + explicit `as` cast at the
  call site, matching the same "declared type must match the real
  descriptor" convention Phoenix Flight established (`task.join() as
  String`) -- this is exactly what makes that verification actually
  pass instead of flagging a real mismatch the moment it's checked. A
  thin `Window` struct (`new`/`should_close`/`clear`/`present`/
  `is_key_down`/`close`) wraps the raw `GLFW::`/`GL11::` calls for the
  ordinary create/loop/destroy lifecycle -- named `new`, not the more
  obvious `open`, because `open` is a real reserved keyword in this
  language (`pub open module ...` visibility) and using it produced a
  real parse error the instant it was tried. Verified end to end with
  a REAL window on real hardware (not just a compile check) via
  Marshmallow: opens, renders a pulsing clear color every frame, closes
  cleanly via Escape or the window's own close button. Depth testing is
  always on (`Window::new` enables `GL_DEPTH_TEST`, `Window::clear` clears
  both the color and depth buffers) -- a real, disclosed scope decision
  (2026-09-11): correctly ordering overlapping 3D geometry is the normal
  case a game engine wants, not the exception. Real geometry (shaders/
  VBOs/draw calls) is a separate follow-up topic, **`graphics`**
  (`stdlib/graphics.hotc`, depends on `window` AND `math`, added
  2026-09-11): `Shader::compile(vertex_src, fragment_src)` compiles and
  links a real GLSL program (printing the real driver info log on
  failure); `Mesh::from_floats(vertices, vertex_count)` uploads a real
  VBO/VAO with a fixed `(x, y, z, r, g, b)` interleaved layout;
  `Mesh::from_indexed(vertices, indices)` adds a real EBO
  (`glDrawElements` -- shared vertices across faces, e.g. a cube's real 8
  corners instead of 36 duplicated ones; the VAO remembers whatever EBO
  was bound while it was bound, so `draw()` never needs to re-bind it
  separately); `Shader::set_mat4(name, m)` uploads a `math.hotc` `Mat4` to
  a named uniform (`glGetUniformLocation`/`glUniformMatrix4fv`,
  `transpose=true` to feed `Mat4`'s own row-major storage directly with no
  re-ordering) -- the piece that makes a per-draw-call model/view/
  projection transform real instead of every mesh being stuck exactly
  where its vertices say it is; `Texture::load(path)` loads any real image
  file `javax.imageio` supports and uploads it as a real GL texture via
  the `GL_BGRA`/`GL_UNSIGNED_INT_8_8_8_8_REV` trick (a Java
  `TYPE_INT_ARGB` pixel, read one at a time via `BufferedImage.getRGB(x,
  y)` into an `IntBuffer`, matches that exact GL format/type pairing
  byte-for-byte on a little-endian machine -- no manual per-channel
  unpacking needed, which HC has no bitshift operator for anyway);
  `Shader::set_int(name, value)` uploads a plain `Int` uniform, also how a
  `sampler2D` gets told which texture unit to sample (`Texture::bind` only
  ever uses unit 0, a real disclosed scope cut -- multi-texture materials
  need real unit management this doesn't do yet); `Mesh::
  from_indexed_textured` adds a SECOND fixed vertex layout (`x, y, z, u,
  v`, no per-vertex color -- the texture supplies color now) alongside
  `from_floats`/`from_indexed`'s `(x, y, z, r, g, b)` shape. Verified end
  to end with REAL rendering on real hardware via Marshmallow: a real
  textured, spinning cube (24 unique vertices -- NOT the 8-shared-corner
  version, since a real per-face UV unwrap needs each face's own
  (0,0)-(1,1) mapping, which a shared corner can't have) with a real
  perspective camera, over a full-screen procedural nebula/starfield
  background shader and a mouse-look + WASD free camera. A real driver
  quirk surfaced adding textures, worth recording: a `#version 150`
  vertex shader with an explicit `layout(location=...)` on an INPUT
  failed to compile on this machine's driver ("'location' : not
  supported for this version or the enabled extensions"), even though
  that's normally available at 150 via `GL_ARB_explicit_attrib_location`;
  bumping to `#version 330 core` (where the feature is unconditionally
  core, no extension needed) fixed it. The failure's own output ordering
  made it look like it belonged to whichever shader compiled NEXT (a
  red herring) -- it was always this one, regardless of compile order.
  See GRAPHICS_IDEAS.md for the full design (including why OpenGL now,
  Vulkan before ship) and its own "Explicitly deferred" list for what's
  still missing (a `Vec3`/`Float` uniform setter, multi-texture support,
  a general vertex-format description, blending). Naming a topic with
  dependencies (`use registry;`)
  automatically pulls those in too — no need to separately write
  `use vec; use option;` as well, though doing so is harmless (a topic
  named more than once, directly or transitively, is only ever merged
  in once).
- **`phoenix` is opt-in ONLY, even with no `use` line anywhere** — the
  one exception to "empty `uses` means inject everything": the "no
  `use` at all" default bundle is still just the original five
  (`option`/`result`/`vec`/`registry`/`io`), unchanged from before
  Phoenix Flight existed. A real background task pool wrapping
  `java.util.concurrent` is real, new, load-bearing behavior — the kind
  of thing that should never silently appear in a program that never
  asked for it, unlike the other five topics (which existed, unnamed,
  as the mandatory prelude long before `use` did). `use phoenix;` is
  required to reach it, full stop.
- **`use` is a bare topic name, not a real module path** — `use vec;`,
  not `use std::vec;` or similar. There's no real module-path resolution
  system behind this (`selfhost/*.hotc`'s own `module a.b.c;` declares a
  JVM-class-naming namespace for the COMPILER's own multi-file source,
  a completely different concern) — `use` just tells `Driver.hotc` which
  of the fixed `stdlib/*.hotc` files to merge in, nothing more. Naming
  an unknown topic is a real, clear error naming it and listing the
  real ones.
- **Split OUT of `selfhost/` on purpose** — `selfhost/`'s own directory-
  mode self-compile (`./gradlew run --args="run selfhost"`) recursively
  scans that whole tree for `.hotc` files; putting the stdlib files
  inside it would mean that scan picks them up a SECOND time as just
  more loose source, double-declaring `Option`/`Vec`/etc. on top of the
  explicit stdlib-merge every compile already does.
- Verified end to end via the new `examples/stdlib_use.hc`: `use
  registry;` alone correctly pulling in `vec`+`option` transitively
  (registration, "last write wins" overwrite semantics, and `Option`
  pattern-matching all working with no other `use` line), plus the
  designed "reference an un-`use`d topic" and "`use` an unknown topic
  name" error cases both failing cleanly rather than crashing or
  silently succeeding.

**Known limitations, real scope cuts, not oversights**:
- **No re-exporting or nested topic composition** — a topic can depend
  on another topic (`registry` on `vec`+`option`), but there's no way
  for a THIRD PARTY file (a real user program, not one of the fixed
  stdlib topics) to declare ITS OWN named module that other programs
  could then `use`. This is standard-library-only opt-in, not a general
  module system for arbitrary user code.
- **No `use topic::Item;` (importing a single name)** — `use topic;`
  always pulls in that whole topic's own full contents; there's no
  finer-grained "just this one type/fn" form.

### `--target`: one compiler, three JDKs

**Fixed 2026-09-10** — real motivating case: a game engine on JDK 25, one
mod line pinned to JDK 17, another to JDK 21, all from the SAME compiler
and the SAME language, with no fork. `SelfhostCLI --target 17|21|25 run
<path>` (or plain `build`/`doc` mode — the flag is recognized before
every other subcommand) picks which JDK the compile targets; omitting it
defaults to `17`, so every existing invocation keeps working unchanged.

```
SelfhostCLI --target 21 run examples/phoenix_flight_chain_async.hc
```

- **The classfile major version genuinely changes** — `61`/`65`/`69` for
  17/21/25 respectively (`44 + <JDK version>`, per the JVM spec),
  verified with real `javap -verbose` output, not just "the flag parsed."
  Every class this compile emits gets it — the entry class AND every
  struct/enum/interface the program declares (`gen_struct`/`gen_enum`/
  `gen_interface`, plus `gen_lambda`'s own synthesized lambda classes).
- **Stdlib topics can require a minimum `--target`** — via `Driver.hotc`'s
  own `min_target_for_topic`, which every real `use topic;` is checked
  against (`add_stdlib_topic_and_deps`) before that topic's own
  transitive dependencies are even processed, so a topic that pulls in
  something version-gated fails naming the ACTUAL topic that's too new
  — including transitively: `use ecs;` pulling in a version-gated
  `phoenix` variant fails naming `phoenix`, not `ecs`, exactly like the
  existing "unknown stdlib module" error already does for a typo'd topic
  name. First real user: `phoenix_virtual` (below), gated at `21`.
- **Language constructs can be gated too, independent of stdlib topics**
  — `arena struct` (see "Off-heap data" below) is gated directly inside
  `compile_program` itself (not `min_target_for_topic`, since it's a
  core language construct, not a `use`-able stdlib topic) at `25` — the
  nearest discrete `--target` level actually `>= 22`, the JDK
  `java.lang.foreign` (what `arena struct` builds on) first stabilized
  in.
- **Real, disclosed scope cuts**: a malformed `--target` value
  (non-digits) crashes with Java's own `NumberFormatException` rather
  than a friendlier compiler error.

### `--classpath`: real extern signature verification, not just trust

**Fixed 2026-09-11** — `Checker.hotc`'s own `verify_extern_signature`
had existed and genuinely worked (proven by the compiler's own
internal self-test, `run_classpath_verify_probe`) since real classpath
reflection was first ported into this subset, but was never reachable
from an ordinary compile — every `extern class`/`extern interface` in
a real project silently degraded to "trust the declaration, no
verification," the exact gap that lets a wrong declared param/return
type compile clean and fail only at RUNTIME with `NoSuchMethodError`.
Found and fixed scaffolding Marshmallow's own extern-heavy next phase
(windowing/graphics bindings) before it needed it.

```
SelfhostCLI --classpath build/libs/mymod.jar;libs/forge.jar run src/main/hc
```

- **A third, independently optional leading flag** — stacks with
  `--target`/`--explain-schedule` the same way (shifting `off` further
  before the source path). Paths are joined with the real platform
  separator (`java.io.File.pathSeparator` — `;`/`:`, read via
  reflection rather than hardcoded), matching exactly what Gradle's own
  `FileCollection.getAsPath()` produces — `hc-gradle-plugin` prepends
  it automatically from the consuming project's own real
  `compileClasspath`, no manual wiring needed on a real project's side.
- **An EMPTY classpath (the default, no flag given) still verifies
  anything findable via the plain context classloader for free** —
  every JDK-only `extern` (this compiler's own stdlib included —
  `Math`/`ArrayList`/`Executors`/...) gets checked with zero extra
  ceremony; a mod/engine-specific `extern` (Forge, LWJGL, ...) needs a
  real `--classpath` to be checked at all. `class_exists_reflect`
  itself is what silently skips a binary name it can't find on
  whatever classpath was actually given, so calling this
  unconditionally for every declared extern method is always safe —
  never a false positive for a binding genuinely not on the classpath.
- **Real, previously-hit ordering bug, found running this against the
  compiler's OWN source**: verifying each extern's methods INLINE,
  within the same loop that registers it, means a forward reference to
  another extern declared LATER in the same file resolves incorrectly
  — `JClassRefX::getMethods` declares `-> [JMethodRefX]`, and
  `JMethodRefX` (declared after `JClassRefX`) wasn't registered yet at
  verification time, so the array-element lookup fell through to `"?"`
  and a completely correct declaration read as a false-positive error.
  Fixed by splitting registration and verification into two separate
  passes over the full `externs` list — register every one first, verify
  only after all of them exist. Same "a checker field populated as a
  side effect isn't ready until the whole pass finishes" class of bug
  this project has hit before (see `ECS_IDEAS.md`'s own bug #2).
- Verified end to end: a real `Probe.class` on a real `--classpath`,
  one `extern` declaration matching its real signature (compiles
  clean) and one deliberately wrong (`String` where the real method
  takes `Int`) rejected with a real compile-time error naming the
  mismatch — plus the full 50-file example regression sweep passing
  unchanged, confirming the new unconditional verification introduces
  no false positives against every existing example.

### Phoenix Flight: a background task pool

**Fixed 2026-09-08** — see "Status" near the top of this doc, and
`PHOENIX_FLIGHT_IDEAS.md` for the original design notes this shipped
from. A real background task pool, `use phoenix;` away, wrapping
`java.util.concurrent`:

```
use phoenix;

@sendable struct Config { multiplier: Int }

fn main() {
    let pool = PhoenixPool::new(4);
    let cfg = Config { multiplier: 2 };
    let task = pool.spawn(|| "{21 * cfg.multiplier}" as PhoenixObject);
    print(task.join() as String); // 42, computed on a background thread
    pool.shutdown();
}
```

- **`PhoenixPool::new(threads)`** wraps a real
  `Executors.newFixedThreadPool(threads)`. **`pool.spawn(task)`** submits
  a lambda (or anything else implementing `PhoenixCallable`) to run in
  the background, returning a `PhoenixTask` handle immediately.
  **`task.join()`** blocks until the task finishes and returns its
  result. **`pool.rebirth(threads)`** discards any queued/running work
  and starts a fresh pool underneath — the "rebirth" a pool needs after
  something in it threw, without reconstructing the `PhoenixPool` value
  itself. **`pool.shutdown()`** matters more than it sounds like it
  should: a `FixedThreadPool`'s worker threads are real, non-daemon JVM
  threads — a program that spawns tasks and never calls `shutdown()`
  will hang after `main()` returns instead of exiting, waiting on
  threads nothing will ever stop. **`pool.join_all_and_shutdown(&tasks)`**
  (`tasks: &Vec<PhoenixTask>`) bundles that lifecycle for the common
  "spawn several, wait for all of them" shape — joins every task in
  order, then shuts the pool down for real, so it's not on the caller to
  remember `shutdown()` separately (this is exactly the pattern the
  self-hosted compiler's own ECS Phase B dispatch code — see `ECS_IDEAS
  .md`'s own "Phase B" writeup — already does by hand around every
  `world.run_all()` call, made reusable).
- **Fixed 2026-09-10** — a task that THROWS is no longer disguised:
  `join()` catches `java.util.concurrent.ExecutionException` (`Future
  .get()`'s own wrapper around whatever the task itself threw) and
  re-throws the REAL cause, so an ordinary `catch (e: SomeException)`
  around `task.join()` catches exactly what a synchronous call to the
  same code would have thrown — nothing leaks the fact that the work
  ran on a background thread at all. See `examples/phoenix_flight_
  advanced.hc`.
- **Fixed 2026-09-10** — **`task.cancel()`** requests the task stop (a
  no-op if it already finished — returns whether the cancel actually
  took, same as `Future.cancel`'s own return value); **`task.is_cancelled()`**/
  **`task.is_done()`** check status without blocking. Cancelling a task
  whose own lambda never checks for interruption has no real effect
  beyond marking it cancelled — a disclosed limit of wrapping `Future`
  rather than something cooperative. **`join_all(&tasks)`** is the
  `join()`-many sibling of `join_all_and_shutdown` — collects every
  task's own result, in order, into one `Vec<PhoenixObject>`, without
  touching the pool itself (more tasks can still be spawned on it
  afterward); `tasks` can't be empty, since there's no placeholder
  `PhoenixObject` to seed an empty result `Vec` with. See
  `examples/phoenix_flight_combinators.hc`.
- **`join()` returns a raw, `Object`-typed handle, cast back to the real
  result type at the call site** (`task.join() as String`, or
  `task.join() as Int` — see "Fixed 2026-09-10" just below) — the same
  "generic Java APIs erase to `Object`" trap this doc's own Java
  interop section already documents for `Map.get`/etc.,
  `java.util.concurrent.Future<T>` included. Safe for REFERENCE-typed
  results (a plain reference reinterpretation, no real `CHECKCAST`
  needed either way, matching this compiler's own "no `CHECKCAST`
  anywhere, trust the declared type" philosophy).
- **Fixed 2026-09-10** — primitive task results (`Int`/`Bool`/`Long`/
  `Float`/`Double`) now work correctly too: `Codegen.hotc`'s own
  `gen_cast` (`expr as Type`) does a genuine box/unbox pair whenever one
  side of a cast is primitive and the other a reference type —
  `Integer.valueOf`/`.intValue()` and its four siblings — rather than
  the old "either `CHECKCAST` a primitive, which the verifier rejects,
  or emit nothing at all and leave a reference on the stack" gap. Spawn
  a primitive-returning task the same way as any other (`|| compute()
  as PhoenixObject`, boxing on the way in), then `task.join() as Int`
  unboxes on the way out. See `examples/phoenix_flight_primitive_result.hc`.
  This is a general `as` cast fix, not Phoenix-Flight-specific — any
  primitive-to-reference or reference-to-primitive cast benefits, join()
  is just the motivating case.
- **The lambda passed to `spawn` needs an explicit cast to the task
  interface's own return type** (`|| compute() as PhoenixObject`, not
  bare `|| compute()`) — a real, disclosed wart, not an oversight: the
  checker requires a lambda body's inferred type to EXACTLY match the
  target interface method's declared return type (no general
  "any reference type widens to `Object`" rule), so the cast is what
  makes the types agree; the cast itself is free at the bytecode level
  (a `String` reference already IS a valid `Object` reference).
- **Two real, more general infrastructure gaps, found and fixed while
  building this, independently valuable beyond Phoenix Flight itself**:
  - A lambda literal passed to a PLAIN (non-`extern`) struct method's
    own `&dyn Interface` param never had its target type inferred —
    only an `extern class`/`extern interface` method call did. Fixed by
    extending `Checker.hotc`'s own `backfill_lambda_args_ty` and adding
    a matching lambda-aware arg loop to `Codegen.hotc`'s own
    `finish_method_call` for the plain-struct-method branch (a new
    `ProgramInfo.method_param_types_list`/`CodeGen.struct_method_param_types`
    registry threads the declared param type names through, mirroring
    the extern-method side's own equivalent). Verified independently of
    Phoenix Flight: an ordinary `interface`/`&dyn Interface` param on a
    plain struct method now accepts a lambda literal correctly.
  - An extern class bound to a real JVM INTERFACE (`ExecutorService`,
    `Future` — both interfaces, not classes, at the real JDK level)
    needs the trailing `extern class ... interface { ... }` marker or
    every call through it emits `INVOKEVIRTUAL` instead of
    `INVOKEINTERFACE` — a real, previously-undocumented trap: this
    compiler has no classpath reflection checking "is this binding
    actually a class or an interface" the way it does for a declared
    METHOD signature, so the mistake compiles clean and fails at
    RUNTIME with `IncompatibleClassChangeError: Found interface ...,
    but class was expected`, not at compile time.
- **Fixed 2026-09-10** — **`pool.spawn_after(&dep, task)`** and
  **`pool.spawn_after_all(&deps, task)`** give basic dependency
  ordering: `task` is only submitted once `dep` (or every task in
  `deps`) has finished. NOT real `CompletableFuture`-style async
  chaining, on purpose — the CALLING thread blocks on the
  dependency/dependencies before ever submitting the new task, rather
  than a pool worker thread parking on it (real trade-off: can't "fire
  a whole chain and walk away," but also can't starve a small pool the
  way a worker-thread-blocking version could). `task`'s own lambda gets
  the SAME compile-time capture check `spawn`'s does — which surfaced a
  real gap fixing this: that check was hardcoded to `PhoenixPool::spawn`
  by (receiver, method-name) pair, so a differently-named method
  submitting the same `&dyn PhoenixCallable` shape (this one) would
  have silently skipped it. Generalized to key on the TARGET
  PARAMETER's own declared type instead (`Codegen.hotc`'s own
  `finish_method_call`) — closes the same gap for calling
  `pool.exec.submit(...)` directly, too, which was completely
  unguarded before this fix. See `examples/phoenix_flight_chaining.hc`.
- **Fixed 2026-09-10** — genuine `CompletableFuture`-based chaining:
  **`pool.spawn_chain(|| ...)`** starts a real async chain (returns a
  `PhoenixChain`); **`chain.then(|prev| ...)`** adds another stage that
  runs the INSTANT the one before it finishes — no thread, caller's or
  worker's, ever blocked waiting for it (the real difference from
  `spawn_after`, above). `chain.join()`/`cancel()`/`is_cancelled()`/
  `is_done()` mirror `PhoenixTask`'s own identically (`CompletableFuture`
  implements `Future`), including the same real-cause exception
  unwrapping. One real, disclosed API difference: each stage's own
  lambda takes ONE parameter (the previous stage's result, still
  `PhoenixObject`-typed) rather than none, even though the common case
  ignores it (`|_| next_step()`) — real `Function<T,R>`
  (`java.util.function.Function`), not `Callable<T>`, is what
  `CompletableFuture.thenApplyAsync` actually composes with, and
  there's no zero-arg overload to fall back to. Needed a `Supplier`
  binding too (`spawn_chain`'s own first stage — `CompletableFuture
  .supplyAsync` doesn't take a `Callable` either) — the capture-safety
  check generalized for `spawn_after` (above) generalized AGAIN here,
  from one hardcoded interface name to a small set
  (`PhoenixCallable`/`PhoenixSupplier`/`PhoenixFunction`). A real,
  previously-hit trap building this: `supplyAsync`/`thenApplyAsync`
  both declare their own executor parameter as `java.util.concurrent
  .Executor`, NOT `ExecutorService` (what `PhoenixPool.exec` itself is)
  — passing it directly compiled clean but failed at RUNTIME with
  `NoSuchMethodError`, since real JVM method resolution matches an
  invoke instruction's own descriptor EXACTLY, not by assignability;
  fixed with an explicit `self.exec as PhoenixExecutor` reference
  reinterpretation at each call site (genuinely sound, not just
  permitted — the real object behind `self.exec` always does implement
  `Executor` too, since `ExecutorService extends Executor`). See
  `examples/phoenix_flight_chain_async.hc`.
- **Real, disclosed scope cuts**: virtual threads are a separate topic
  (below), not this one — `PhoenixPool` itself stays pinned to
  `Executors.newFixedThreadPool`.
- Verified end to end via `examples/phoenix_flight.hc`: a real
  background thread computing a result and joining it back on the main
  thread, a `@sendable`-marked struct's field read correctly from
  inside the task, and (see "Compile-time concurrency safety" below)
  the designed capture-safety error case.

### Phoenix Flight, virtual threads: `use phoenix_virtual;`

**Fixed 2026-09-10** — real motivating case: the `--target 21`/`25` mod
line/game-engine tiers (see "`--target`" above) shouldn't have to wait
for the `--target 17` mod line to catch up before getting real value
from `Executors.newVirtualThreadPerTaskExecutor()` (stable since JDK
21). A NEW, separate stdlib type from `PhoenixPool` itself
(`stdlib/phoenix_virtual.hotc`) — not a modification of `PhoenixPool` in
place — gated at `--target 21` or higher via `min_target_for_topic`, so
a `--target 17` compile never sees `PhoenixVirtualPool` exist at all:

```
use phoenix_virtual;

fn main() {
    let pool = PhoenixVirtualPool::new();
    let task = pool.spawn(|| expensive_io() as PhoenixObject);
    print(task.join() as String);
    pool.shutdown();
}
```

- **`PhoenixVirtualPool::new()`** wraps a real
  `Executors.newVirtualThreadPerTaskExecutor()` — no thread-count
  parameter, unlike `PhoenixPool::new(threads)`, since a virtual-thread
  executor starts a fresh, cheap virtual thread per submitted task
  rather than drawing from a fixed pool of real OS threads. **`spawn`**/
  **`shutdown`** are identical in shape to `PhoenixPool`'s own, and
  reuse `PhoenixTask`/`PhoenixCallable`/`join_all` directly rather than
  redeclaring them — a `Future`/`Callable`-based task behaves
  identically from the caller's own side regardless of what runs
  underneath it.
- **`use phoenix_virtual;` under `--target 17`/`21` below the gate**
  fails with a clear compiler error naming the actual requirement
  (`module 'use phoenix_virtual;' requires --target 21 or higher`),
  same convention every other gated topic uses.
- Verified end to end: `--target 17` rejects `use phoenix_virtual;` at
  compile time; `--target 21` compiles and (on a JDK 21+ runtime)
  correctly runs a task on a virtual thread; classfile major version
  confirmed at `65` via `javap -verbose` on both the entry class and
  `PhoenixVirtualPool` itself. See `examples/phoenix_virtual_threads.hc`.

### Compile-time concurrency safety: `@sendable`

**Fixed 2026-09-08** — the actual point of Phoenix Flight existing as
more than a thin `ExecutorService` wrapper, and the answer to "how does
a language with no real move/borrow checker yet still get SOME
compile-time concurrency safety": `pool.spawn(|| ...)`'s lambda can only
capture a `Copy` primitive (`Int`/`Bool`/`Long`/`Float`/`Double`),
`String`, or a struct explicitly marked `@sendable` — capturing a plain
mutable struct into a background task is a compile error, not a data
race waiting to happen the first time two threads touch it at once.

```
struct World { tick: Int }
@sendable struct Config { multiplier: Int }

fn main() {
    let pool = PhoenixPool::new(2);
    let world = World { tick: 5 };
    let cfg = Config { multiplier: 2 };

    pool.spawn(|| "{world.tick}" as PhoenixObject);   // compile error -- World isn't @sendable
    pool.spawn(|| "{cfg.multiplier}" as PhoenixObject); // fine -- Config is @sendable
}
```

- **`@sendable struct Foo { ... }`** — a bare compiler directive (no
  quoted binary name, same shape as `@must_use`), only valid on `struct`
  declarations. It's the AUTHOR's own assertion that every field is
  safe to hand across a thread boundary — NOT verified field-by-field
  this pass (same "declared type is truth" honesty `extern class`
  already gets elsewhere in this checker). Marking a struct holding a
  mutable reference to shared state `@sendable` doesn't make it
  actually safe; it just tells the checker to stop flagging it.
- **Where the check lives**: `Codegen.hotc`'s own `finish_method_call`,
  gated specifically on the receiver being `PhoenixPool` and the method
  being `spawn` — not a general "no capturing non-`Copy` values in ANY
  lambda" rule (ordinary closures capture freely, exactly as before).
  Reuses the EXISTING `collect_lambda_captures` free-variable walker
  (already built for closure codegen) rather than a new checker-side
  duplicate — a codegen-time `CodegenError` is just as real a compile
  failure as a checker error anywhere else in this compiler.
- **Why this, and not full ownership-derived scheduling**: real
  compiler-verified "these tasks can't alias the same `&mut` state" —
  the kind of thing an ECS scheduler would derive from `&`/`&mut`
  component-access annotations — is a MUCH bigger, separate design that
  presupposes a real move/borrow checker existing first (still not
  implemented at all, see "Status" near the top of this doc). `@sendable`
  is the smaller, achievable-today piece: not "prove this task can't
  race with that one," just "stop the single most common and dangerous
  mistake — accidentally sharing mutable state into a background
  thread — at compile time instead of finding out at 2 AM." See
  `IDEAS.md`'s own "ECS with ownership-derived system scheduling" entry
  for the bigger design this doesn't attempt to replace.
- Verified: the `World`/`Config` example above — capturing `world`
  (plain struct, not `@sendable`) into `spawn` fails with a real,
  specific compile error naming the variable and its type; capturing
  `cfg` (`@sendable`) succeeds and the task reads `cfg.multiplier`
  correctly on the background thread.

### `&mut` vs `&`

**Real in `selfhost/` now** (fixed 2026-09-09, Phase 3 of the roadmap —
see "Compile-time safety roadmap" below): `&`/`&mut` parse and work
mechanically (a `&mut` parameter can mutate the caller's struct), AND
every rule below is now actually enforced by `Checker.hotc`, not just
described as intent. One disclosed narrowing from the design as
originally built for the retired Kotlin compiler: the exclusivity rule
(third bullet below) covers a call's ARGUMENT list, but not yet the
implicit receiver of a method call (`p.foo(p)`-shaped self-aliasing) —
see "Compile-time safety roadmap" for why.

Borrows come in two kinds, and they're enforced, not just parsed:

- `&T` (shared, read-only): can read fields, cannot assign to them.
- `&mut T` (exclusive, read-write): can assign to fields (`p.hp = 5;`).
  Only a `var` local's fields, or a `&mut`-borrowed param/`&mut self`, can be
  assigned into — a `let` local's fields, or one reached through a plain
  `&`, are compile errors.
- Taking `&mut x` requires `x` itself to be declared `var` (not `let`).
- Within a single call's ARGUMENT list, the same variable can't be
  borrowed both `&mut` and anything else at once — `foo(&mut p, &p)` and
  `foo(&mut p, &mut p)` are both rejected, but `foo(&p, &p)` (multiple
  shared borrows) is fine. (The implicit receiver isn't included in this
  check yet — see the note above.)
- Struct fields can't themselves be reference types (`struct S { x: &Int }`
  is rejected) — there's no lifetime/region tracking, so a stored borrow
  could dangle; keeping `&`/`&mut` scoped to function parameters and call
  sites is what makes the above checks sound without one.

This is still a purely compile-time discipline, same as move-checking — the
JVM's GC keeps every object alive regardless, so nothing here prevents a
real memory-safety bug the way it would with manual memory management; it
catches aliasing/mutation bugs by construction instead, the way Rust's
borrow checker does for code that would otherwise compile fine and misbehave
at runtime.

### Compile-time safety roadmap: lexical scoping, move checking, borrows

A small, deliberately-scoped-down plan toward the headline move/borrow-
checking feature described above (still "not implemented in `selfhost/`
at all" as of this section) — motivated by an explicit design question:
how much of Rust's own borrow checker actually makes sense on the JVM?
The answer that shaped this plan: the JVM's GC removes Rust's core
memory-safety motivation (dangling references are impossible here
regardless), so the real payoff isn't memory safety — it's *aliasing
discipline* (catching "two owners both mutating the same object"
mistakes at compile time) plus the scheduling signal `IDEAS.md`'s own
"ECS with ownership-derived system scheduling" entry already wants
(`&`/`&mut` component access as the parallelism proof). Given that,
this plan takes on a MUCH smaller job than Rust's real borrow checker
by adopting one hard rule up front: **a struct can never hold a
borrowed reference (only owned values), and a function can never
return a borrow.** That single restriction is what eliminates the
hardest 80% of Rust's own design — lifetime parameters, regions,
non-lexical lifetimes, elision rules, variance — none of which exist,
or are planned, here. Four phases:

1. **Real lexical scoping** — done, see the "Fixed 2026-09-08" Status
   entry above. Prerequisite for everything below: move-checking and
   borrow-checking both need to know precisely when a binding starts
   and stops being live, which a flat, whole-function variable table
   can't express.
2. **Move checking** — done, see the "Fixed 2026-09-09" Status entry
   above for the full design and disclosed cuts (struct-literal field
   values don't move; a method's receiver and `self`'s own ref-kind
   aren't tracked; extern/generic calls are exempted; `match`/`try`
   arms and loop bodies don't get the same divergence-aware branch
   merge `if`/`else` does). The language's own already-documented
   headline feature: passing a struct by value moves it, and using the
   old variable afterward is a compile error. Close to a linear-type
   check; valuable on its own even before borrows exist — and
   surfaced/needed real `&`/`&mut` PARAM tracking (`Param.is_ref`,
   previously pure syntax with no record kept at all) as a genuine
   prerequisite this plan hadn't separately called out.
3. **Borrow checking** — done, see the "Fixed 2026-09-09" Status entry
   above for the full rule set and disclosed cuts (the exclusivity
   check doesn't yet cover a method call's own implicit receiver).
   Turned out narrower in practice than "real lexical scoping for
   borrows" originally suggested: since `&`/`&mut` here only ever
   appear as function/method PARAMETERS (never as a local variable a
   `&x` expression could be bound to — `&expr` at the expression level
   is, and stays, a transparent no-op — and never stored in a struct
   field, rule 4 below), there's no actual borrow LIFETIME to track
   lexically at all. The real content of this phase ended up being
   four independently-checkable rules (field-assignment mutability,
   `&mut` requires a mutable binding, single-call exclusivity, no
   reference-typed struct fields) rather than a scope-tracking pass —
   "early Rust, pre-NLL" was the right INSTINCT (cheap, block-local,
   no flow-sensitivity), it just didn't need a scope stack of its own
   the way move-checking's `moved`/`var_scopes` did, since mutability
   is a fixed property of a binding for its whole lifetime here, not
   something that changes moment-to-moment the way "moved" does.
4. **Stop there** — no lifetime parameters, no elision, no variance, no
   `'static`. The "no stored/returned borrows" rule (rule 4 above) is
   what makes stopping here sound instead of just incomplete, and this
   is genuinely the end of this roadmap — no Phase 5 planned. What's
   left beyond this is the bigger, separate ECS/ownership-derived
   scheduling design `IDEAS.md` already tracks, which consumes what
   Phases 1–3 built rather than extending them further.

### Generics (monomorphized — no boxing)

**Fixed 2026-09-02**: `examples/generics.hc`, matching the exact
`Box<T>` pattern below, used to fail with `unknown struct or enum
variant 'Box'` (constructing a bare generic struct literal with no
type-inferring context) and then, once that was fixed, "could not
infer generic type argument" (calling a generic fn whose param type
wraps the type argument, `fn unwrap<T>(b: Box<T>) -> T`). Both were
real type-inference gaps, not codegen corruption — see "Status" near
the top of this doc. Verified working correctly now, including passing
a `Box<Player>` (a non-`Copy` struct) through `unwrap`.

`struct`/`fn` can take type params (`struct Box<T> { value: T }`,
`fn identity<T>(x: T) -> T`). Type args are always inferred from
call/literal-site argument types — there's no explicit `foo<Int>(x)` call
syntax yet. Unlike Java/Kotlin/Scala generics, these are **never** erased to
`Object`: every distinct instantiation (`Box<Int>`, `Box<Player>`, ...) is
monomorphized into its own real class/method the first time it's used, with
real `int`/`boolean` fields where a primitive type arg is used — no boxing,
ever. Verified: `javap` on a compiled `Box<Int>` shows `public int value;`,
not `public Integer value;`. The tradeoff versus type erasure is class-file
duplication if the same generic struct gets instantiated with many different
reference types — that's a code-size cost, not a perf one, and an
opt-in "share one Object-backed impl across reference-type instantiations"
mode (like .NET does) is a reasonable phase-2 add if it matters in practice.
An unbounded `T` can only move around (store, pass, return) — it can't
call a method or do arithmetic on it, since the checker has nothing to
validate that against. `<T: SomeTrait>` lifts that restriction somewhat
(see "Bounded generics" below) — a real, disclosed narrower version of
that guarantee is now in `selfhost/`. `struct`/`fn` generics here are
single-param only; a generic ENUM can take a second type param
specifically for the `Result<T, E>` shape — see "Two-type-parameter
generics" below.

### Methods (`impl` blocks)

`impl StructName { fn method(&self, ...) -> T { ... } }` adds methods,
called as `value.method(...)`. `self` (owned, moves the receiver like any
other by-value struct param) and `&self` (borrowed) both work and are
move-checked the same as regular params — `p.describe()` where `describe`
takes owned `self` moves `p`, and a second `p.describe()` is a compile
error. Methods desugar to ordinary static functions named `Owner$method`
under the hood — there's no virtual dispatch/vtable, so no
inheritance/polymorphism yet, just plain structs with attached functions.
Generic impls work too: `impl<T> Box<T> { fn get(&self) -> T { ... } }`
monomorphizes per instantiation exactly like generic free functions.
**Fixed 2026-09-04**: this leading-`<T>` spelling used to fail to parse
outright in `selfhost/` — only the trailing form (`impl Box<T> { ... }`)
was ever accepted. See "Status" near the top of this doc and "Generics"
above for the full story, including a separate bug it surfaced.

### Arrays

`[Int]` is the type of an array of `Int` (`&[Int]` / `&mut [Int]` for
borrowed access, same rules as everything else). Backed directly by real
JVM arrays (`int[]`, `Player[]`, ...) — no wrapper struct, no boxing for
primitive element types. Two literal forms: `[1, 2, 3]` (elements, type
inferred from the first one) and `[value; count]` (a `count`-length array
filled with `value`, `count` can be a runtime expression; `value` must be
`Copy` — `[Player{...}; 5]` is rejected, since repeating a move-only value
would alias the same object into every slot). `arr[i]` reads, `arr[i] = v`
writes (same mutation rule as struct fields: needs `var` or `&mut`), and
`arr.length` reads the length. Arrays are move-only like structs; `[T]`
works as a generic parameter type and monomorphizes per element type
exactly like `Box<T>` does. Bounds checking is free — it's just the JVM's
own `*aload`/`*astore` array-bounds checks, no extra codegen needed.

### Loops: `for x in a..b` / `for x in arr`

One `for` syntax covers both counting and iterating a collection, Rust-style
(there's no separate "foreach" keyword — a range and an array are both just
things you can write `for x in ...` over):

- `for i in 0..5 { }` — `i` counts `0, 1, 2, 3, 4` (exclusive end); `0` and
  `5` can be arbitrary `Int` expressions, evaluated once each, not
  re-evaluated per iteration.
- `for x in arr { }` — `x` is bound to each element in turn; the array is
  only borrowed for the loop (never moved), and struct elements' fields are
  readable through `x` exactly like anywhere else.
- Both compile to a tight index-based bytecode loop (`IF_ICMPGE`/`IINC`/
  `GOTO`), no iterator-object allocation — arrays are indexed directly,
  ranges just count an int local.
- A bare `a..b` outside a `for` header is a compile error — ranges aren't a
  general-purpose value in this language, only a loop header shape.
- Braces are optional for a single-statement body, on both `for` and `while`
  (deliberately not `if` — see "`if`/`match` as expressions" below for why a
  one-liner conditional already has a better answer than a dangling
  statement): `for x in arr print(x);` and `while cond do_thing();` both
  work, and nest for free (`for x in xs for y in ys print(x, y);`) since a
  brace-less body is just one more `statement()`, recursively.

### Destructors: `impl StructName { fn drop(&mut self) { } }`

**Broken in `selfhost/` today**: calling `drop` fails with `codegen for
'Call' to 'drop' not implemented this phase` — verified via
`examples/drop.hc`. Also, `selfhost/checker/Checker.hotc` explicitly
disclaims doing move/borrow checking at all in this phase, which this
feature depends on. This section describes the design as built for the
retired Kotlin compiler.

Rust-style automatic cleanup: a `let`/`var` binding's `drop` method fires
when it goes out of scope still owned (unmoved) — at the end of its block,
or right before an early `return` that skips past it. This is what makes
`arena struct`-adjacent resource wrappers viable (e.g. a struct owning a
confined arena that closes it in `drop`), and is generally useful for
"make sure this got cleaned up" logic.

- Declared with the exact signature `fn drop(&mut self)` (no other params,
  no return value) — anything else is a compile error pointing at that
  requirement.
- Fires in reverse declaration order (last-declared, first-dropped), same
  as Rust — verified with three `let`s where the drop order comes out
  `c, b, a`.
- Fires once per loop iteration for a variable declared inside a loop body
  (it's a fresh binding each iteration, so cleaning it up each time is
  correct) — verified with a `while` loop.
- Fires correctly on every early-`return` path, not just falling off the
  end of a function — verified with a conditional early return that drops
  variables from multiple enclosing scopes in the right order before the
  return actually happens.
- A moved/returned value is correctly *not* dropped where it's moved from
  — only wherever it ends up still owned and unmoved gets the drop call.
  This falls directly out of reusing the existing move-checker's
  moved-state, rather than being a separate mechanism.
- Manual/early drop: the built-in `drop(x)` function (not a method —
  `x.drop()` is a compile error, specifically to prevent a double-drop
  footgun) consumes `x` immediately and calls its destructor right there;
  because it moves `x`, the normal end-of-scope drop correctly skips it
  afterward — verified there's no double `drop` call.

**Known limitation, stated plainly**: drop is *not* recursive/structural
like Rust's. Dropping a struct does not cascade into dropping its fields,
even if a field's own type has a `drop` impl — only the struct being
directly bound to a `let`/`var` (or manually `drop()`-ed) gets its own
`drop` called. A function parameter received by value (not `&`/`&mut`) is
also not currently auto-dropped when the function returns without doing
anything else with it. And generic structs can't have an *automatically*
firing `drop` yet (the built-in `drop(x)` function does still work for a
generic instance, since unlike the automatic scope-exit path it goes
through the same on-demand monomorphization as a normal method call).
These are real gaps versus Rust, not just caveats — worth knowing before
relying on this for anything safety-critical.

### Interfaces: `interface`, `impl X for Struct`, `&dyn X`

**Fixed 2026-09-03**: a struct implementing more than one interface —
verified with two unrelated interfaces on one struct, each callable
directly and through its own `&dyn` reference, including an interface
default method. The one thing STILL not supported from the "same-named
methods from unrelated interfaces" scenario further below is real
completeness checking (verifying every required method actually got
provided, and rejecting a genuinely ambiguous same-named default) —
see that section's own header. `interfaces2.hc` still fails to parse
for an unrelated reason (generic `impl<T>` isn't supported yet — see
"Generics" above and "Bounded generics" below). (A separate
`NoSuchMethodError` that used to hit `examples/interfaces.hc` was a
classpath-shadowing bug in `run_cli`, not an interfaces bug — fixed
2026-09-02; basic single-interface `&dyn` dispatch works correctly.)

The first feature in this language that needs genuine dynamic dispatch —
every other method call compiles to a direct static call, resolved
entirely at compile time (that's what makes them zero-cost). An interface
reference doesn't know its concrete type until runtime, so calling through
one costs a real (small) `INVOKEINTERFACE`, layered on top, not
free — the tradeoff for actual polymorphism.

```
interface Describable {
    fn name(&self) -> String;                        // required
    fn tag(&self) -> String {                         // has a default
        return "[thing] " + self.name();
    }
}

struct Player { pname: String, hp: Int }
impl Describable for Player {
    fn name(&self) -> String { return self.pname; }   // tag() inherits the default
}

struct Item { iname: String }
impl Describable for Item {
    fn name(&self) -> String { return self.iname; }
    fn tag(&self) -> String { return "<item> " + self.iname; }  // overrides the default
}

fn announce(d: &dyn Describable) { print(d.tag()); }  // works for either concrete type
```

- **Required vs. default methods**: a signature with no body (`fn name(&self) -> String;`)
  must be provided by every `impl Interface for Struct` block — missing one is a
  **compile-time** error naming exactly which method(s) are missing. A signature
  *with* a body is a default: implementers may override it or just inherit it —
  verified both ways above (`Player` inherits `tag()`'s default, `Item` overrides it).
- **`&dyn Interface`**: a reference that can point to any struct implementing that
  interface, dispatched dynamically. There's no owned `dyn X` — always behind `&`/`&mut`.
  A concrete struct is compatible wherever a `&dyn Interface` it implements is expected
  (real subtyping — the only subtyping relationship in this language; everywhere else
  types must match exactly).
- **Two call forms, verified to produce identical results**: calling `.tag()` on a
  concretely-typed `Player`/`Item` still resolves at compile time (`INVOKEVIRTUAL`
  against the known class — a real instance method now, not this language's usual
  static-desugared one), while calling it through `&dyn Describable` is genuinely
  dynamic (`INVOKEINTERFACE`) and picks the right override at runtime.
- **Default method bodies can't touch struct internals.** Inside a default
  implementation, `self` is typed as `&dyn Interface`, not the concrete struct — so
  a default body can only call *other* interface methods through `self` (like
  `self.name()` above), never access a field directly. This exactly matches how
  Java/Rust default methods work, and falls out for free: `self` being `Ty.Dyn`
  rather than `Ty.Struct` means the existing field-access checker rejects it
  automatically, no special-case code needed for the restriction itself.
Finding and fixing this feature also forced a real, previously-latent bug fix:
call-site bytecode descriptors used to be built from the *caller's* argument
types rather than the *callee's* declared parameter types — harmless as long as
argument and parameter types were always identical, which was true everywhere
until interfaces introduced actual subtyping (passing a concrete `Player` where
`&dyn Describable` is expected). Now fixed to always build descriptors from the
callee's own signature ([CodeGen.kt](src/main/kotlin/hc/codegen/CodeGen.kt)),
which is what should have been happening all along.

#### Generic structs implementing interfaces

`impl<T> Labeled for Box<T> { fn label(&self) -> String { return self.name; } }`
works — a generic struct's interface impl is deferred until a concrete
instantiation exists (`Box<Int>`, `Box<Person>`, ...), then processed exactly
like a concrete one, so each instantiation gets its own correctly monomorphized
instance methods. Real limitation, not just a caveat: since interfaces
themselves don't take type parameters, this only works for methods whose
signature doesn't need to depend on `T` — `interface Gettable<T> { fn get(&self) -> T; }`
would need *generic interfaces*, which don't exist here. Finding this also
surfaced two real bugs, both fixed: the checker was looking up a generic
instantiation's interface membership under the wrong name (the shared
template's name instead of the specific instantiation's, e.g. `Box` instead
of `Box_Int`), and — the same leaf-node-sharing hazard fixed earlier for plain
generic methods — an interface method's body was being reused by reference
across different instantiations instead of deep-copied, so checking `Box_Person`
after `Box_Int` clobbered `Box_Int`'s type annotations and produced a
bytecode-verifier crash (`VerifyError: Box_Int not assignable to Box_Person`)
before the fix.

#### Interface inheritance (supertraits): `interface Sub: Super, Super2 { }`

A struct implementing `Sub` automatically satisfies `Super` too — verified
both ways: a struct that only writes `impl Sub for Struct` (no separate
`impl Super for Struct`) is still accepted wherever `&dyn Super` is expected,
and `Sub`'s default method bodies can call `Super`'s methods through `self`.
Under the hood this is real JVM interface inheritance (`Sub`'s class file
`extends`-equivalent-lists `Super` the same way Java interfaces do), so
`Super`'s default methods are inherited automatically by anything
implementing `Sub` with no extra codegen needed for that part.

#### Same-named methods from unrelated interfaces

**Partially real in `selfhost/` today**: a struct implementing more
than one interface is now real (fixed 2026-09-03 — see "Interfaces"
above), including calling each interface's own methods directly or
through its own `&dyn` reference, and interface default methods. What
this section specifically describes below — a real completeness check
verifying every struct claiming an interface actually provides every
required method, and a compile-time error for a genuinely ambiguous
same-named default from two unrelated interfaces — is NOT implemented:
`selfhost/` has no interface-completeness checking at all currently (a
struct missing a required method isn't caught at compile time; it
surfaces as a runtime `AbstractMethodError` the first time something
calls the missing method, unrelated to the multi-interface fix above).
The description below is the design as built for the retired Kotlin
compiler, kept for reference.

Fixed properly (in the retired Kotlin compiler) rather than left unspecified. Two cases:

- **A struct provides one override that satisfies both.** `impl A for Widget { fn info(&self)... }`
  plus a separate, empty `impl B for Widget { }` where `B` also declares `info`
  — this now compiles, because on the JVM one instance method named `info`
  satisfies any number of interfaces' requirement for it simultaneously. This
  needed a real fix: completeness ("did every required method get provided")
  used to be checked per impl block in isolation, so it couldn't see that a
  requirement from a *different* interface's impl block was already satisfied.
  Now checked once, after all of a struct's impl blocks are processed,
  against everything it provides collectively.
- **Neither is overridden and it's genuinely ambiguous** (two unrelated
  interfaces each defaulting `info`, no override anywhere) — this is a
  **compile-time error** naming both interfaces, not a silent pick. This isn't
  just about type-checking convenience: even if the two defaults happened to
  have identical signatures, the struct's class has no single method to link
  an `INVOKEVIRTUAL` against, so silently picking one would risk a
  `IncompatibleClassChangeError` at runtime — the same "inherits unrelated
  defaults" situation real `javac` also refuses to compile.

### Bounded generics: `<T: Trait>`

**Partially ported to `selfhost/`, fixed 2026-09-03** — see "Status"
near the top of this doc. Verified via `examples/bounded.hc`, both a
compiling call (a `Player` implementing the bound) and a rejected one
(a plain `Int`, which doesn't). **Only the first of the two guarantees
described below is implemented** — validation at each instantiation
site. The second (the body itself checked once, abstractly, against a
synthetic merged-bound interface) is NOT — a bounded generic's body is
still checked exactly like an unbounded one, fully duck-typed against
whichever concrete type happens to be substituted in at first use. The
rest of this section describes the FULL design as built for the
retired Kotlin compiler; paragraphs describing the second guarantee are
kept for reference, not a description of current `selfhost/` behavior.

```
fn apply_damage<T: Damageable>(x: &mut T, amount: Int) -> Int {
    x.damage(amount);
    return x.hp();
}
```

Worth understanding *why* this needs to exist at all, given how generics
work here: since a generic body is only ever checked once fully
monomorphized (never abstractly against a bound, unlike real Rust), an
**unbounded** `T` can already call any method that happens to exist on
whatever concrete type it's eventually instantiated with — genuinely
duck-typed, C++-template-style, errors surfacing per-instantiation rather
than at the generic declaration. A declared bound gives two guarantees on
top of that, not one:

- **Validation at every instantiation site**: `checkTypeParamBounds` runs
  when `T` gets substituted with a concrete type, confirming that type
  actually implements every bound trait, producing one clear error naming
  exactly what's missing — verified: instantiating `apply_damage` with a
  plain `Int` (which doesn't implement `Damageable`) is rejected with
  `'Int' for type param 'T' doesn't implement [Damageable]`, right at the
  call site, rather than failing confusingly deep inside the body.
- **The body itself is checked once, abstractly, against the bound** —
  every bounded type param is substituted with a real `&dyn` value (a
  synthetic interface merging every bound trait's methods, built
  specifically for this check) instead of any concrete type, so a method
  call the body makes on a `T`-typed value only resolves if the bound
  actually declares it. This is the guarantee that makes a bound worth
  writing at all: without it (this was a real gap in the original
  bounded-generics pass, fixed once it was flagged as a lynchpin blocking
  other, bigger features — see `IDEAS.md`), a bounded generic's body was
  checked exactly like an
  *unbounded* one, fully duck-typed against whichever concrete type
  happened to be substituted in wherever it was first called — so a body
  could compile fine against the first instantiation tried (accidentally
  relying on some method that merely happens to exist on that one
  concrete type, not on anything the bound actually promises) and only
  fail, confusingly, at a *different*, later call site instantiating the
  same generic with a type that satisfies the identical declared bound
  but lacks that extra method. Verified directly: a body calling a method
  outside the declared bound (`x.describe()` inside `<T: Damageable>`,
  where `describe` isn't part of `Damageable`) is now rejected with `'T:
  Damageable' has no method 'describe'` — even though the one concrete
  type actually used at the call site (`Player`) really does have a
  `describe()` method. The check runs exactly once per generic, cached,
  entirely independent of whether or how many times it's ever actually
  instantiated — matching how a real bound's correctness shouldn't depend
  on which instantiations someone happened to exercise.

Multiple bounds combine with `+`: `<T: A + B>` — checked against the
union of both traits' methods, and rejected up front if the two bounds
declare conflicting signatures for the same method name (no real type
could implement both compatibly anyway).

**Known limitation**: this second guarantee only covers top-level
generic fns where *every* type param has a declared bound. A mixed
`<T: Trait, U>` (some params bounded, some not) has no way to abstractly
type the unbounded `U` at all, so it falls back to the original
per-instantiation-only checking for such fns — not a regression, just
not yet improved by this fix. Generic struct/impl methods (where the
receiver itself would need abstracting, not just its params) aren't
covered yet either.

### Two-type-parameter generics: `enum Result<T, E> { ... }`

**Fixed 2026-09-03, scoped to enums** — see "Status" near the top of
this doc. Verified via `examples/error_handling.hc`'s own `safe_parse`.

```
enum Result<T, E> {
    Ok { value: T },
    Err { error: E },
}

fn safe_parse(s: String) -> Result<Int, String> {
    try {
        let n = Integer::parseInt(s);
        return Result<Int, String>::Ok { value: n };
    } catch (e: NumberFormatException) {
        return Result<Int, String>::Err { error: "bad number: " + s };
    }
}
```

- **Enums only.** `struct`/top-level-`fn` generics stay single-param
  (`Box<T>`, `identity<T>`) — `Result<T, E>` (a sum type where each
  variant typically only touches ONE of the two params) was the actual
  motivating gap, not a general "N-param generics" ask, so
  `struct Pair<A, B>` isn't supported.
- **Construction always needs the fully explicit qualified form**,
  `Base<Arg1, Arg2>::Variant { ... }` — bare-literal inference
  (`Ok { value: n }` alone, relying on a surrounding `return`'s declared
  type the way the one-param case already can) isn't attempted here.
  Inferring TWO independent type arguments from whichever ONE of them a
  single variant's own fields happen to touch is a genuinely different,
  harder problem than the existing one-param inference already solves —
  not attempted this pass, so every construction site must spell out
  both type arguments.
- **Monomorphized exactly like the one-param case**, just with two
  independent substitutions instead of one — a two-arg mono name
  (`"Result$Int$String"`) is real, produces a real compiled class per
  distinct `(T, E)` pair actually used, same "no boxing, no erasure"
  story every other generic in this language already has.
- `Result<T, E>` is now part of the always-injected self-hosted prelude
  (previously omitted specifically because this feature didn't exist
  yet — see `Driver.hotc`'s own `prelude_source`).

### `@must_use`: compiler-enforced "don't silently drop this return value"

**Fixed 2026-09-03** — see "Status" near the top of this doc. Verified
all three ways: correct use compiles, a bare discarded call is
rejected, and `@must_use` on a `Unit`-returning fn is rejected too.

```
@must_use
fn try_spawn(pos: BlockPos) -> SpawnResult { ... }

fn main() {
    try_spawn(pos);         // compile error: return value of 'try_spawn' must be used
    let result = try_spawn(pos);  // fine
}
```

- A bare compiler directive — `@` followed by a bare `IDENT`, no quoted
  binary name and no `RuntimeVisibleAnnotations` attribute — never
  reaches bytecode, purely a compile-time check. Distinguished from a
  real `@"binary.Name"(...)` annotation right at parse time, by whether
  a `STRING` or an `IDENT` follows the `@`.
- Scoped to top-level `fn` only this pass — `impl` block methods
  (instance and static/factory) aren't covered yet, a narrower cut than
  the retired Kotlin compiler's own later-widened version.
- The checker rejects `@must_use` on a `Unit`-returning fn outright
  (nothing to enforce using), and separately errors on any bare
  `Expr.Call` to a must-use fn sitting alone as a statement (nothing —
  no `let`, no `return`, no argument position — consuming its result).
- **Narrower than fully general, same spirit as the original design**:
  extern class methods/static calls (Java interop) aren't covered —
  this stays scoped to *HC's own* top-level fns. A struct method
  reached through interface/`&dyn` dispatch also isn't covered (only a
  bare top-level `Call`, not a `MethodCall`, is checked).

### Structured `///` doc comments (compiler-understood, not Javadoc-style text blobs)

**Fixed 2026-09-03** — see "Status" near the top of this doc. Verified
three ways: valid `@param`/`@returns`/`@see` targets compile fine, an
unresolved `@see` is a real compile error, and a `////` divider line
correctly does NOT attach as a doc comment.

```
/// Attacks an enemy and returns the resulting damage.
/// @param target The enemy being attacked.
/// @returns The damage dealt.
/// @see Enemy.health
fn attack(target: &Enemy) -> Int { ... }
```

A doc comment there is a real `DocComment` node the parser attaches to
the `struct`/`fn` it precedes (`Parser.hotc`'s own `doc_comment_block`),
not opaque text a separate tool re-parses later. `///` is a real lexer
token (`TokType.DOC_COMMENT`) rather than discarded trivia the way a
plain `//` comment is — `////` (four-plus slashes) deliberately stays
an ordinary comment, same convention Rust uses, so a decorative divider
line doesn't suddenly start attaching itself to the next declaration.
`@param`/`@returns`/`@example`/`@warning`/`@see`/`@deprecated` are all
structured tagged fields; an untagged `///` line continues whatever
section came before it (so a tag's text can wrap across multiple
lines), except `@see`, whose target is always exactly one line.

- **`@see` resolves against the real symbol table.** `Checker.hotc`'s
  own `check_doc_sees` runs once, at the very end of checking (once
  every symbol table is fully populated), resolving both bare names
  (`SomeStruct`, `some_fn`, an enum, an interface, an extern class alias)
  and dotted forms (`SomeStruct.field`, `SomeEnum.Variant`) — and errors
  on anything that doesn't resolve. "No dead doc links, ever" is a real
  compile error, not just an aspiration.
- **Scoped to top-level `fn`/`struct` only** — an `impl` block's own
  methods don't carry doc comments yet (this language's `impl` is
  struct-only, so there was no natural extension point to wire up
  without more design than the feature needed for v1).
- **`hc doc <path> [outFile]` renderer, fixed 2026-09-03** — see
  "Status" near the top of this doc. `SelfhostCLI doc <path> [outFile]`
  (default `api.md`) runs the same parse/checker pipeline `build`/`run`
  use, then renders `Program`'s own doc-comment data to Markdown once
  that succeeds — one heading per documented `struct`/`fn`, `@param`s as
  a bullet list, `@warning` as a blockquote. **Deliberately diverges
  from the retired Kotlin compiler's own version here**: that renderer
  marked an unresolved `@see` `(unresolved)` inline in its output; this
  one never needs to, because `check_doc_sees` already rejects a dead
  `@see` link as a real compile error before rendering ever runs — no
  output file is written at all if any `@see` in the program is broken,
  same as a real error blocks `build`/`run`. See
  [examples/docs_demo.hc](examples/docs_demo.hc) for a real worked
  example, verified end to end.
- Repeating a tag (`@returns` twice, say) keeps only the LAST
  occurrence's text, a real, disclosed simplification versus perfectly
  faithful multi-block accumulation.

### Composition delegation sugar: `impl Interface for Struct by field`

**Fixed 2026-09-03**: ported into `selfhost/` — `by field` parses, and
`compile_program` materializes a real forwarding method (`fn m(&self,
...) { return self.field.m(...); }`, or a bare call + fallthrough
return for a `Unit`-returning method) for every interface method not
explicitly overridden, using the same "materialize before checking"
mechanism already used for interface default methods (delegation runs
first, so it wins over a default when a method qualifies for both).
Verified for both a value-returning and a `Unit`-returning delegated
method, through direct calls AND `&dyn Interface` dynamic dispatch, and
through an interface default method that itself calls the delegated
one — `examples/delegation.hc` now compiles and runs correctly.

```
impl Describable for Car by engine { }   // no method bodies needed
```

Any interface method not explicitly overridden in the block gets a
forwarding method synthesized automatically — `fn describe(&self) -> String
{ return self.engine.describe(); }` — rather than requiring hand-written
boilerplate to compose one struct's behavior out of another's. Verified
both dispatch paths still work correctly through the delegate: calling
`.describe()` on a concretely-typed `Car` and calling it through `&dyn
Describable` both correctly forward to `engine`. Whether the delegate
field's type actually satisfies the interface isn't specially validated —
the synthesized forwarding call just goes through the same checker pass as
any other method call and fails there if it doesn't, so the error (if any)
looks identical to a hand-written mistake, not a special delegation-only
error path.

### Enums and `match` — a flat tagged union, deliberately not a class hierarchy

**Fixed 2026-09-02**: this exact `Shape` example (`examples/enums.hc`)
used to fail at runtime with `InstantiationError: Shape`. Root cause
wasn't enum codegen at all — `run_cli`'s child-process classpath put
`selfhost/bootstrap` (which happened to contain a stale, unrelated
demo class also named `Shape`, compiled as an *interface*) ahead of
the freshly-compiled program's own output directory, so the JVM loaded
the wrong `Shape` class. Enum tag equality, variant construction, and
`match` all verified working correctly now that the classpath order is
fixed and the stale bootstrap classes are gone.

```
enum Shape {
    Circle { radius: Int },
    Square { side: Int },
    Point,
}

fn area(s: Shape) -> Int {
    var result = 0;
    match s {
        Circle { radius } => { result = radius * radius * 3; }
        Square { side } => { result = side * side; }
        Point => { result = 0; }
    }
    return result;
}
```

This is the composition-over-inheritance answer to "how do you get a fixed,
closed set of variants without struct subtyping": every variant of an enum
compiles into **one single JVM class**, holding an `Int` `tag` field plus
*every* variant's fields side by side (namespaced `Variant$field`, so two
variants can reuse a field name without colliding). Constructing a variant
just sets the tag and that variant's own fields, leaving the others at
their default zero value — cheap and simple, at the cost of wasting some
memory per instance (every value carries every variant's fields, not just
its own). No sealed-class hierarchy, no per-variant subclass, no
inheritance anywhere in the representation — deliberate, given the
project's composition-first direction elsewhere.

- **Construction**: `Circle { radius: 2 }` reuses struct-literal syntax
  exactly; a unit variant (no fields) is constructed bare, e.g. `Point`
  (no braces). Variant names share a single global namespace with struct
  names (and each other) — two enums can't declare a variant with the same
  name, and a struct can't share a name with any variant. This is a real,
  deliberate constraint to keep bare-name resolution (`Point` without an
  `enum::` qualifier) unambiguous; documented as a limitation, not
  something to work around.
- **`match` works as a statement or a value** — see "`if`/`match` as
  expressions" below for the value form. Field patterns bind by position
  against the variant's declared field order (`Circle { radius }` binds a
  fresh local named `radius`) — no renaming, no nested patterns, no
  literal-value matching.
- **Exhaustiveness is enforced at compile time.** Every variant must be
  covered by name, or a `_` wildcard arm must be present (which must come
  last) — verified: a `match` missing two of three variants and no
  wildcard is rejected, naming exactly which variants are missing.
- The scrutinee is **consumed** (moved) by `match`, matching Rust's default
  `match x { }` semantics for a non-`Copy` `x` — fields get destructured by
  value into the arm's fresh bindings.
- **Known limitation**: no destructor support for enums (unlike structs'
  `drop`) — a real gap versus a "real" enum system, not just a caveat.

#### Generic enums: `enum Option<T> { Some { value: T }, None }`

**Fixed 2026-09-02**: `examples/genericenum.hc` used to fail with
`unknown struct or enum variant 'Some'` for the same reason plain
generics did above (a bare variant literal, `let s = Some { value:
"hello" };`, with no type-inferring context) — see "Generics" above
and "Status" near the top of this doc. Verified working correctly now.

Monomorphized exactly like a generic struct — the same on-demand
instantiation machinery, just applied to enum variants. The interesting
part was the *unit* variant case: `Some { value: 5 }` can infer `T` from
the field it's given, but `None` has no fields at all to infer anything
from. That only resolves in a position where the target type is already
known — `return None;` against a function declared to return `Option<T>`,
or `let x: Option<Int> = None;` — which needed threading an `expectedTy`
hint into the checker for exactly those two call sites (nowhere else).
Verified with a function returning `Option<Int>` from two different
call sites (found vs. not-found) plus a separate `Option<String>` in the
same program, both instantiations existing simultaneously and correctly.
A bare `None` anywhere the type *isn't* already known from context (no
type-directed inference beyond that) is a clear compile-time error, not a
silent wrong guess.

Building this also forced fixing a real, previously-invisible bug in
generic substitution: instantiating a generic with a `&dyn Trait` or
array type argument produced an unresolvable mangled type name (e.g.
`"Dyn_Block"`) instead of a real type reference — nobody had hit it
because nothing had substituted a generic parameter with those kinds of
types before. Fixed by making the substitution machinery convert a
resolved type back into a proper reference-shaped `TypeRef` (setting
`isRef`/`isDyn` correctly) rather than just using its mangled name.

### `if`/`match` as expressions

```
let min_x = if pos1.getX() < pos2.getX() { pos1.getX(); } else { pos2.getX(); };

let area = match shape {
    Circle { radius } => { radius * radius * 3; }
    Square { side } => { side * side; }
    Point => { 0; }
};
```

`if`/`match` still work as plain statements exactly as above; this is the
same syntax additionally usable anywhere an expression is expected (a `let`
RHS, a call argument, ...) — reachable because the parser now also accepts
a leading `if`/`match` as a primary expression, not just at statement
position, so this is purely additive (existing statement-form code is
completely unaffected).

- **Deliberately not full Rust-style block-tail-value semantics.** Each
  branch/arm is still an ordinary `{ ... }` block using the normal
  statement grammar (every statement needs its usual `;`) — but to be used
  as a value, that block must contain **exactly one statement**, itself a
  bare expression (`radius * radius * 3;`, not `let`/`if`/`return`/...).
  This is a ternary-shaped if/match-expression (ML/Kotlin's `if` model),
  not "the last statement in any block, sans semicolon, is the block's
  value" — that would need a real grammar change (distinguishing a
  semicolon-terminated statement from a value-producing tail expression);
  this needs none, since `{ expr; }` already parsed as an ordinary
  statement block before this feature existed, just always discarded.
  A branch with more than one statement, or whose one statement isn't a
  bare expression, is a clear compile-time error naming the branch.
- **`if`-expressions require `else`** (no value without one); chained
  `else if` works the same as the statement form.
- **Both branches/every arm must produce the same type** — a compile-time
  error otherwise, naming both/all the mismatched types.
- **`match`-expressions keep the statement form's exhaustiveness rules**
  (every variant covered by name, or a `_` wildcard) — same error, same
  wording. An exhaustive match with no explicit wildcard (every variant
  gets its own arm) compiles to a runtime trap on the "no arm matched"
  path instead of a fallthrough, purely to satisfy the JVM bytecode
  verifier's static stack-shape check (every other path already leaves a
  value on the stack) — provably unreachable given the checker's own
  exhaustiveness guarantee, same idiom already used for a function that
  falls off its end without a `return`.
- **No null-narrowing inside an if-expression's condition** — the
  `if x != null { ... }` narrowing the statement form supports (see
  "Nullable extern references") isn't applied here; out of scope for this
  first pass.

### Sealed interfaces and `match`-by-concrete-type

**Fixed 2026-09-02**: `examples/sealed.hc` used to fail with
`NoSuchFieldError: radius` when matching a concrete-typed field — same
root cause as the enum `InstantiationError` above (a stale, same-named
demo class in `selfhost/bootstrap` shadowing the real one; see
"Status" near the top of this doc). Verified working correctly now.

```
sealed interface Shape {
    fn area(&self) -> Int;
}
struct Circle { radius: Int }
impl Shape for Circle { fn area(&self) -> Int { return self.radius * self.radius * 3; } }
struct Square { side: Int }
impl Shape for Square { fn area(&self) -> Int { return self.side * self.side; } }

fn describe(s: &dyn Shape) -> Int {
    match s {
        Circle { radius } => { return radius * 100; }
        Square { side } => { return side * 1000; }
    }
}
```

`sealed` marks an interface's implementer set as closed and tracked by the
compiler (in declaration order) — today that mostly means "known", since
there's no multi-file module boundary yet to actually *enforce* closure
against, but what it unlocks is real: `match` over a `&dyn SealedInterface`
value, dispatching by **concrete struct type** rather than by enum
variant, with the same compile-time exhaustiveness checking as an enum
`match` (verified: a `match` over `&dyn Shape` missing `Square` and no
wildcard is rejected, naming it).

This is the actual answer to "cheaper dynamic dispatch," and it's honest
about what it does and doesn't buy you: it isn't a hand-rolled dispatch
table competing with `INVOKEINTERFACE` for raw speed (the JVM's JIT
already does inline caching there, which is typically about as fast for
the small implementer counts this is realistic for). What it *does* do is
let the matched case skip dynamic dispatch **entirely** — the pattern
match itself compiles to an `instanceof`/`checkcast` chain (there's no
shared tag field the way an enum has one, since implementers are
independent struct classes, not variants of one flat type), and once
matched, any subsequent method call inside that arm on the destructured
value is a completely ordinary, zero-cost static call, not a virtual one.
The saving isn't in how the match itself dispatches — it's that matching
converts "I don't know the concrete type" into "I do," once, up front,
rather than paying dynamic dispatch on every subsequent call.

### `Vec<T>` and `Registry<T>` — a small self-hosted prelude

Two things worth having, and neither needed new compiler machinery to
build: they're just ordinary Hot Chocolate source
([`Prelude.kt`](src/main/kotlin/hc/Prelude.kt)), parsed and merged into
every compiled program before checking — real "eating your own dog food,"
not special-cased in the compiler. Since generic declarations are only
ever checked once instantiated, including this unconditionally costs
nothing for a program that never references it.

- **`Vec<T>`** is a genuine growable array (`[T]` alone is fixed-size).
  Verified pushing 5 elements past an initial capacity of 4, correctly
  triggering the grow-and-copy path. Building it needed one real checker
  relaxation: `[value; count]` used to require a `Copy` value; it now
  allows any type, **aliasing** the same reference into every slot rather
  than moving it (memory-safe on the JVM regardless of element type —
  storing one reference into N array slots is exactly what any array-fill
  already does under the hood). That's what lets `push` fill a freshly
  grown backing array using the just-pushed value as a throwaway filler
  for the slots it's about to overwrite anyway, without needing a
  separate "default value for T" concept this language doesn't have.
- **`Registry<T>`** is the deliberate, extensible counterpart to `enum`:
  built on `Vec`, string-keyed, `.get` returns `Option<T>` so a missing
  key is a normal, matchable value rather than a crash. This is the
  intended tool for a Minecraft-style block/item registry — `enum` is for
  a fixed, known-at-compile-time set of cases; `Registry<dyn Trait>` is
  for a set that grows as more things get registered into it. Verified
  end-to-end with `Registry<dyn Block>` holding *two different* concrete
  struct types (`StoneBlock`, `DirtBlock`) registered under separate keys,
  `.get()` returning the right one dynamically dispatched through `Option`,
  and a missing key correctly producing `None`.

Getting the registry case working end-to-end surfaced one more real,
previously-hidden gap and fixed it: `let x: &dyn Trait = &someStruct;`
was only *validating* that the assignment was compatible, not actually
widening `x`'s own stored type to the trait — so `x` stayed typed as the
concrete struct internally, meaning passing `x` into anything doing type
*inference* (like a generic function call) would infer the concrete type,
not the trait, silently defeating the entire point of writing the
annotation. Fixed so a compatible declared type now genuinely widens the
variable's type from that point on, not just checks against it.

**Known limitation, stated plainly**: no true `Vec::new()`/empty
construction — both `Vec` and `Registry` need at least one seed value
(`vec_of`/`registry_new`) to build their initial backing array from, since
this language has no per-type default/zero value to fill an empty one
with. A real gap versus a "real" collections library, not just a caveat.

### Off-heap data (`arena struct`, `Arena<T>`)

This is the actual phase-2 payoff mentioned early on: hot-path game data
(particles, ECS components, grids) that lives in contiguous native memory
instead of as N separate heap objects, cutting GC pressure and improving
cache locality. Built on the JDK's Foreign Memory API (`java.lang.foreign`),
stable since JDK 22 — no preview flags needed.

- `arena struct Particle { x: Int, y: Int, alive: Bool }` declares an
  off-heap struct. Fields are restricted to `Int`/`Bool` for now (4 bytes
  each, naturally aligned, no packing yet — a phase-3 concern) — no
  `String`/nested-struct/array fields, since those are heap objects and
  can't live in raw memory without a lot more machinery.
- `arena Particle[count]` allocates a buffer of `count` elements as one
  contiguous `MemorySegment`, auto-reclaimed once unreachable
  (`Arena.ofAuto()` — see the honesty note below).
- The type of that buffer is `Arena<Particle>`.
- `buf[i].x` reads, `buf[i].x = v` writes — compiled directly to
  `MemorySegment.get`/`set` at `i * sizeof(Particle) + offsetof(x)`, no
  intermediate object ever materialized. A bare `buf[i]` (without a
  field) is a compile error — there's no way to hand back "the whole
  off-heap struct" as one JVM value, so only field-at-a-time access exists.
  Same `var`/`&mut` mutation gating as everything else.
- `Particle { x: 1, y: 2 }` (a struct literal) does **not** work for an
  arena struct — construct the buffer with `arena Particle[count]`
  instead; the checker gives a specific error pointing at that syntax.

**Honesty about what this is and isn't**: this is *contiguous off-heap
storage with automatic reclamation*, not a fully manual arena with
explicit `free`. `Arena.ofAuto()` still lets the JVM decide when memory
is reclaimed (once the `MemorySegment` is unreachable) — real manual
control (`Arena.ofConfined()` + explicit `close()`) would need
destructor/RAII-style "drop" semantics this language doesn't have yet.
What you get today is the actual perf-relevant part for a game engine —
one contiguous allocation instead of N GC-tracked objects, real
`int`/`boolean` storage, no per-element overhead — without yet promising
deterministic manual deallocation.

**Toolchain note**: the compiler itself only needs JDK 17 (it emits
`java.lang.foreign` calls as plain ASM bytecode, no compile-time
dependency on those classes). But *running* a program that uses
`arena struct` needs a JDK 22+ to actually execute — `hc run` shells out
to a real `java` process for this reason (rather than executing
in-process via reflection, like it used to).

**Fixed 2026-09-10** — `arena struct` is now gated at *compile time* by
`--target` (see "`--target`" above): compiling any program containing
one under `--target 17` or `21` fails immediately with a clear error
naming the requirement (`'arena struct Particle' needs
java.lang.foreign, stable only since JDK 22 -- compile with --target
25`) instead of producing a `.class` file that only fails much later,
at runtime, on exactly the JDK it claimed to target. `25` is the gate
(not `22`) because this compiler's own `--target` levels are only
17/21/25 — `java.lang.foreign` is still preview at 21, and there's no
"22" level to name instead. Verified against `examples/arena.hc` at all
three levels: `17`/`21` reject at compile time, `25` compiles clean to
major version `69`.

**Not implemented in `selfhost/` today**: the `$HC_JAVA_HOME` fallback
and the friendly pre-flight version check described above don't exist
— `grep -r "HC_JAVA_HOME"` across the repo returns nothing, and
`run_cli` in `selfhost/Driver.hotc` unconditionally launches whatever
`java` is on `PATH` with no version check. This is now a secondary
concern rather than the only guard: a program compiled at `--target 25`
still needs a real JDK 22+ `java` on `PATH` to *execute* — running it on
an older JDK still crashes with a raw `NoClassDefFoundError:
java.lang.foreign.Arena` rather than a friendly message, exactly like
before this fix (the fix is at compile time, not runtime).

### Java interop

Calling into existing, externally-compiled JVM classes (the JDK itself,
or eventually Minecraft/Forge/Fabric APIs) via a declared, trusted class
shape — no classfile introspection, no `.jar` parsing at compile time:

```
extern class Random = "java.util.Random" {
    fn new() -> Self;
    fn nextInt(self, bound: Int) -> Int;
}
extern class JMath = "java.lang.Math" {
    fn max(a: Int, b: Int) -> Int;
}

fn main() {
    let r = Random::new();
    print(r.nextInt(6));
    print(JMath::max(3, 9));
}
```

- `extern class Alias = "binary.class.Name" { ... }` declares an alias
  for an existing JVM class by its binary name (dots become slashes
  automatically). Every method's parameter/return types must be the
  *exact* JVM-erased signature.
- Inside the block, `fn new() -> Self;` (no `self`, name `new`) is a
  constructor — `Alias::new(args)` compiles to `NEW` + `DUP` + args +
  `INVOKESPECIAL <init>`. `fn name(self, ...) -> T;` is an instance
  method — `value.name(args)` compiles to `INVOKEVIRTUAL`. Anything else
  (no `self`, name isn't `new`) is a static method — `Alias::name(args)`
  compiles to `INVOKESTATIC`.
- An extern-typed value (`Random` above) is move-only, like a struct —
  same borrow/ownership rules apply.

**Honesty about what this is and isn't**: this is exactly analogous to a
hand-written FFI declaration file (a `.d.ts` for TypeScript, an `extern
"C"` block in Rust) — the compiler *trusts* the declaration completely,
there's no classfile parser cross-checking it against the real class. A
wrong signature (wrong param types, wrong binary name, a method that
doesn't actually exist) type-checks fine and fails at runtime with a
`NoSuchMethodError`/`NoClassDefFoundError`, not a compile error.

**Known limitation**: generic Java APIs erase to `Object` at the
bytecode level (type erasure) — wrapping something like
`List<String>.get(int)` needs the declaration to say the erased
signature (`Object`, not `String`), or the `INVOKEVIRTUAL`/
`INVOKEINTERFACE` link-fails at runtime even though the declaration
"looks" correct.

#### `extern class Alias = "..." interface { ... }`: when the real target is an interface, not a class

```
extern class JList = "java.util.List" interface {
    fn add(&mut self, item: JObject) -> Bool;
}
extern class Component = "net.minecraft.network.chat.Component" interface {
    static fn literal(text: String) -> Component;
}
```

Plenty of real JVM APIs you'd reach for with `extern class` are actually
backed by an `interface`, not a class — `java.util.List`/`Set`/
`Iterator`/`Map.Entry`, `java.lang.Iterable`, `java.util.function
.Supplier`, and plenty of framework types (Minecraft's own `Component`,
`Registry`, ...). The JVM encodes a call to an interface's method
differently at the constant-pool level than a call to a class's — an
*instance* method needs `INVOKEINTERFACE` (not `INVOKEVIRTUAL`), and even
a *static* one (legal since Java 8) needs an `InterfaceMethodref`
constant, not a plain `Methodref`, despite still using the `INVOKESTATIC`
opcode. Since there's no classfile introspection here (same "trust the
declaration" honesty as everything else under `extern`), the source has
to say which kind the real target is: append `interface` right after the
binary name string, on any of the three declaration forms (explicit,
`use { }`, or bare/lazy).

**Getting this wrong is a real, silent-until-link-time failure mode**,
not just a style nicety — a wrong invoke kind compiles clean and even
`javap`-verifies as a structurally valid classfile, then throws
`IncompatibleClassChangeError: ... must be InterfaceMethodref constant`
(static) or the `INVOKEVIRTUAL`-against-an-interface equivalent
(instance) the moment the JVM actually *links* the call — verified
against a real Forge mod calling `Component.literal(String)` and
`List.add(Object)`, both of which crashed exactly this way before this
existed. If you're declaring an `extern class` for something and aren't
sure whether it's really a class or an interface, check with `javap` —
guessing wrong is invisible until the call actually runs.

#### `extern interface`: implementing a Java interface

`extern class` lets Hot Chocolate code call *into* an existing JVM
class. `extern interface` is the other direction: it lets an HC struct
*implement* an existing JVM interface, so real Java code can call back
into it — exactly the shape a mod loader needs (`ModInitializer`, event
listeners, `Runnable`-style callbacks, ...):

```
extern interface Run = "java.lang.Runnable" {
    fn run(&self);
}
extern class JThread = "java.lang.Thread" {
    fn new(target: &dyn Run) -> Self;
    fn start(self);
    fn join(self);
}

struct Greeter { name: String }
impl Run for Greeter {
    fn run(&self) { print("hi from a real java.lang.Thread, " + self.name); }
}

fn main() {
    let t = JThread::new(&Greeter { name: "Rin" });
    t.start();
    t.join();
}
```

`impl Run for Greeter` goes through the exact same machinery as any
other `impl Interface for Struct` (a real JVM instance method on
`Greeter`'s own class, method-name-ambiguity/completeness checking, the
works) — the only difference is codegen has `Greeter`'s class
`implements` the *real* binary name (`java/lang/Runnable`) instead of a
class this compiler generated, so `INSTANCEOF`/`INVOKEINTERFACE` from
genuine Java code sees a real `Runnable`. See `examples/extern_interface.hc`.

**Known limitations**: an `extern interface` can never be `sealed` —
arbitrary external Java code could implement it too, which would make
`match`-by-concrete-type's closed-set assumption unsound, so the syntax
doesn't allow it. It also can't `extends` another interface, and its
methods can't have default bodies (there's no interface class here to
attach one to — every method must be overridden). Both are the same
"declare the trusted shape, nothing more" scope cut as `extern class`.

#### Lambda literals: `|params| expr`

**Fixed 2026-09-02**: ported into `selfhost/` for the first time — a
real `Expr.Lambda` AST node, checker type inference, and codegen that
synthesizes a real implementer class per lambda literal, matching the
design below (originally shipped only in the now-retired Kotlin
compiler). One difference from the design as originally written: the
target doesn't have to be specifically an `extern interface` — any
single-method interface works, local or extern, since codegen already
had to handle both cases for `impl X for Struct` anyway and there was
no reason to special-case lambdas narrower than that. Verified working
for zero- and multi-param lambdas, capturing both plain locals and
struct fields, targeting `Unit`- and value-returning methods alike, as
an argument to a top-level `fn`, an extern instance method, or an
extern constructor/static method.

`impl Run for Greeter` above is the right tool when a Java callback
needs real named struct fields or gets implemented in more than one
place. For the common case — a one-off, single-call-site functional
argument (`Supplier<T>`, `Function<T,R>`, a Forge `Predicate`/
`BiConsumer`, ...) — a lambda literal skips the struct+impl ceremony
entirely:

```
extern interface JSupplier = "java.util.function.Supplier" {
    fn get(&self) -> JObject;
}
extern class PacketDistributorTarget = "net.minecraftforge.network.PacketDistributor$PacketTarget" {}
extern class PacketDistributor = "net.minecraftforge.network.PacketDistributor" {
    static PLAYER: PacketDistributor;
    fn with(&self, supplier: &dyn JSupplier) -> PacketDistributorTarget;
}

fn target_for(p: ServerPlayer) -> PacketDistributorTarget {
    return PacketDistributor::PLAYER.with(|| p as JObject);
}
```

`|params| expr` (or `|| expr` for zero params) is only ever legal
directly as a call argument whose declared param type is a **single-
method `extern interface`** — there's no way to spell a lambda's type
out explicitly, so it's always inferred from that context, never
declared. The body is one expression, not a statement block (the same
"ternary-shaped" cut `if`/`match`-as-expressions already use) — for
anything that needs real locals or multiple statements, factor the logic
into an ordinary top-level `fn` and forward to it from a one-line lambda
(`|buf| SomePacket::decode(buf)`) instead.

A lambda **captures enclosing locals by move**, exactly like passing the
same value to any other call — `p` above can't be read again after this
call. (As with every other move-related claim in this doc, this is the
design intent, not something currently enforced — see "Status" near
the top: move/borrow checking isn't implemented in `selfhost/` at all
yet, so `p` compiles fine if read again today, silently.) There's no
`invokedynamic`/`LambdaMetafactory` involved: each lambda literal
compiles to its own small, real implementer class (one `cap$name`
field per capture, set from a real constructor call at the lambda's
own use site), the same strategy javac itself used for anonymous inner
classes before Java 8 — verified via `javap`: a real `NEW`+`DUP`+
captures+`INVOKESPECIAL` at the use site, a real class implementing
the target interface with a `cap$name` field per capture at the
definition site. **Fixed 2026-09-03**: bare method-reference syntax
(`Type::method` used directly as a lambda-typed argument, no
`|x| Type::method(x)` wrapper needed) now works too — see "Status" near
the top of this doc for the design and verification. Desugars entirely
to the same synthesized-implementer-class machinery above (a real
`Lambda` node built from the method-reference at check/codegen time),
not a second emission path.

#### Reference-type casts (`as`, compiling to `CHECKCAST`)

Generic Java APIs erase their type parameters to `Object` at the bytecode
level — `List<Component>.add(Component)` really has the erased descriptor
`add(Ljava/lang/Object;)Z`, so an `extern class` declaration for it must say
`item: JObject`, not the concrete type. `as` bridges that gap in both
directions:

```
extern class JList = "java.util.List" {
    fn add(&mut self, item: JObject) -> Bool;
}
extern class JObject = "java.lang.Object" {}

tooltip.add(Component::literal("hi") as JObject);   // concrete -> erased
let s = (someObject as MyExternType);                // erased -> concrete
```

- `as` now accepts a reference-type cast (both sides `Ty.isObjectRef()` —
  `Str_`/`Struct`/`Enum`/`Dyn`/`JavaExtern`/`Array`) as an alternative to its
  existing numeric-conversion form, compiling to a plain `CHECKCAST` against
  the target's descriptor.
- Same honesty tradeoff as `extern class` itself: a wrong cast type-checks
  fine and throws `ClassCastException` at runtime, not a compile error — this
  is a trusted assertion, not a verified one.
- This is what actually makes generic JDK collections (`List`, `Map`,
  iterators, ...) usable from HC: declare the erased `Object` signature, then
  cast on the way in and out.

#### Runtime type checks: `expr is Type`

**Fixed 2026-09-03**: ported into `selfhost/` — a new `Expr.IsCheck`
AST node, sharing `as`'s own precedence tier and class-name resolution
(`Parser.hotc`'s `cast()` now handles both `AS` and `IS` in the same
loop, and `Codegen.hotc`'s `gen_is` reuses `gen_cast`'s own
`resolve_elem_class_name` for a real `INSTANCEOF`). Verified working
against an extern-class receiver (`x is JList`) and a `&dyn
InterfaceName` receiver (`d is Describable`) alike, and chained with
`as` in the same expression (`obj as JObject is JList`).

`as`'s boolean-returning counterpart — a real JVM `INSTANCEOF`, not a cast:

```
extern class JObject = "java.lang.Object" {}
extern class JList = "java.util.List" {}

fn describe(x: JObject) {
    if x is JList {
        print("it's a list");
    }
}
```

- Same scope as `as`'s reference-type form: both sides must be an object
  reference type (`Str_`/`Struct`/`Enum`/`Dyn`/`JavaExtern`/`Array`) — `is`
  doesn't apply to `Int`/`Long`/`Float`/`Double`/`Bool`, same as real Java.
- Deliberately non-consuming (unlike `as`, which moves its operand) — `x is
  Foo` is a peek at `x`, not a move, the same reasoning `x == null`'s own
  non-consuming check already uses. `x` stays fully usable right after,
  including inside the `if` body.
- Same precedence tier as `as` (binds tighter than arithmetic, looser than
  unary), and the two chain together freely in either order.

#### `extern class` fields — static (`Alias::FIELD`) and instance (`recv.FIELD`)

**Ported to `selfhost/`, fixed 2026-09-03** — see "Status" near the top
of this doc. Verified working correctly, both read and write.

Some real JVM APIs expose state through a `public` field instead of a
getter method — `Integer.MAX_VALUE`/`Style.EMPTY`/`ForgeRegistries.ITEMS`
(static), or `Minecraft.player`/`Minecraft.font` (instance). `extern
class` bodies can declare either alongside their methods:

```
extern class JInteger = "java.lang.Integer" {
    static MAX_VALUE: Int;
}
extern class JPoint = "java.awt.Point" {
    fn new(x: Int, y: Int) -> Self;
    x: Int;
    y: Int;
}

fn main() {
    print(JInteger::MAX_VALUE);       // 2147483647 -- static, via ::
    let p = JPoint::new(3, 4);
    print(p.x);                       // 3 -- instance, via .
    p.x = 10;                         // write -- instance, via . =
    print(p.x);                       // 10
}
```

- `static NAME: Type;` declares a static field, read via `Alias::NAME`
  (no parens — that's what distinguishes it from `Alias::method()`),
  compiling to `GETSTATIC`, written via `Alias::NAME = value`, compiling
  to `PUTSTATIC`. `NAME: Type;` (no `static`) declares an instance field,
  read via `recv.NAME` — the exact same syntax a struct field already
  uses — compiling to `GETFIELD` against the receiver, written via
  `recv.NAME = value`, compiling to `PUTFIELD`. Either form is
  distinguished from a method purely by the absence of `fn` (no parens
  follow the name either way).
- Same trust model as everything else under `extern`: the declared type is
  never checked against the real field's actual type, so a wrong one
  type-checks fine and throws at runtime (`NoSuchFieldError` or a
  `ClassCastException`-shaped failure at the first real use), not a
  compile error. Reading or writing the wrong access kind
  (`Alias::instanceField` or `value.staticField`) *is* a compile error,
  though — the checker tracks which kind each declared field is and
  points you at the right syntax.
- Explicit-signature form only — not resolvable via the `use { }`/lazy
  reflected forms (a field has no "candidate list" the way an overloaded
  method name does, so reflecting it isn't the same shape of problem —
  just not wired up).

#### Property-style access: `recv.field` reading as `recv.getField()`/`recv.isField()`, writing as `recv.field = v` → `recv.setField(v)`

**Fixed 2026-09-03, both read AND write** — see "Status" near the top
of this doc. Corrects an earlier audit note in this doc that assumed
the retired Kotlin compiler's read-only version had already carried
over; it hadn't — `selfhost/` had no getter/property-sugar handling of
any kind before this pass, checked and confirmed by grepping
`Checker.hotc` for it.

```
extern class JFile = "java.io.File" {
    fn new(path: String) -> Self;
    fn getName(&self) -> String;
    fn isDirectory(&self) -> Bool;
}
extern class JDateFormat = "java.text.SimpleDateFormat" {
    fn new(pattern: String) -> Self;
    fn setLenient(&self, lenient: Bool);
    fn isLenient(&self) -> Bool;
}

fn main() {
    let f = JFile::new("hello.txt");
    print(f.name);        // -- f.getName(), a Java-bean getter
    print(f.directory);   // -- f.isDirectory(), a Bool-returning "isX" getter

    var df = JDateFormat::new("yyyy-MM-dd");
    df.lenient = false;   // -- df.setLenient(false), a Java-bean setter
    print(df.lenient);    // -- df.isLenient()
}
```

`recv.field` first tries to resolve as a real declared instance field (see
above); when there isn't one, it falls back to a zero-arg `getField`/
`isField` instance method (Java bean-getter naming — `field` capitalized
and prefixed) before giving up, cutting the getter-call ceremony that
dominates interop-heavy code (`stack.getTag()`, `pos1.getX()`,
`enemy.getHealth()`). `recv.field = value` is the write-side mirror,
falling back to a single-arg `setField` method the same way.

- **A real declared field always wins** — this is pure fallback sugar,
  never a silent choice between two things: if `Alias` has both a
  declared instance field named `foo` and a `getFoo`/`isFoo`/`setFoo`
  method, `.foo`/`.foo = v` always reads/writes the field.
- **Both directions are independently resolved** — a struct/`extern
  class` can have a getter with no matching setter (read-only property
  sugar, same as a real Java class with no setter) or vice versa;
  each fallback only fires for the specific access it's needed for.
- Verified end to end: `f.name`/`f.directory` (read), `df.lenient = false`
  followed by reading `df.lenient` back (write then read, confirming the
  real `setLenient(false)` call actually landed), and a genuinely
  unknown field name rejected with `unknown field 'bogusThing' on
  extern class 'JFile' (also tried 'getBogusThing()'/'isBogusThing()')`.
- **Scoped to every `extern class` declaration selfhost has** — there's
  no `lazy`/`use { }` reflected form in `selfhost/` at all this phase
  (only the explicit-signature form exists), so the retired Kotlin
  compiler's own "non-lazy only" caveat doesn't currently narrow
  anything further here.
- A setter's own declared return type is trusted, whatever it is (not
  necessarily `Unit`/void, matching real fluent-builder Java APIs) —
  its value is generated and then discarded, so `recv.field = value`
  still evaluates to `value` itself either way, the same "assignment is
  a value-producing expression" convention every other assignment in
  this language already has.
- Same "trust the declaration" honesty as everything else under `extern`:
  a `getField`/`isField`/`setField` method that doesn't actually exist
  on the real class type-checks fine (the checker only ever sees
  whatever signature you declared) and throws at runtime.

#### Java annotations: `@"binary.Name"(arg: value, ...)`

**Ported to `selfhost/`, fixed 2026-09-03** — see "Status" near the top
of this doc. Verified end to end via `javap`, including array
arguments. `@serializable` (the OTHER `@`-prefixed feature, right
below) is a separate, still-unported compiler *directive* — see that
section's own header.

Some Java frameworks find your code through annotation *reflection*
instead of a registration API you can call — Forge's event bus is the
motivating case: `@Mod.EventBusSubscriber(modid = MODID, value =
Dist.CLIENT)` on a class plus `@SubscribeEvent` on each handler method is
how `MinecraftForge.EVENT_BUS`/the mod-bus find your listeners at all.
`@"binary.Name"` immediately before a top-level `struct` or `fn` declares
a real Java annotation, emitted as a genuine classfile
`RuntimeVisibleAnnotations` attribute — not a compiler-internal marker,
something reflection actually sees:

```
@"net.minecraftforge.fml.common.Mod$EventBusSubscriber"(
    modid: "yourmodid",
    value: [enum("net.minecraftforge.api.distmarker.Dist", "CLIENT")],
)
pub struct MyClientEvents {}

@"net.minecraftforge.eventbus.api.SubscribeEvent"
pub fn onRenderOverlay(event: &RenderGuiOverlayEventPost) {
    // ...
}
```

- `@"binary.Name"` alone is a marker annotation (no args). `@"binary.Name"
  (argName: value, ...)` supplies named arguments.
- Three argument value shapes exist, deliberately — annotation arguments
  are compile-time constants baked directly into the classfile attribute,
  not executable code, so this is its own small grammar, not the general
  expression language: a string literal (`"..."`), a real Java `enum`
  constant via `enum("binary.Name", "CONST")` (compiles to
  `AnnotationVisitor.visitEnum`, exactly what an enum-typed annotation
  argument is stored as at the classfile level — there's no "reference" to
  an enum constant the way `GETSTATIC` reads one at runtime; annotation
  metadata is inert data read back by reflection, never executed), or an
  array `[value, value, ...]` of either (`AnnotationVisitor.visitArray`) —
  needed whenever the real attribute's declared type is itself an array
  (`Mod.EventBusSubscriber.value()` is `Dist[]`, not a single `Dist`,
  which is why the example above wraps it in `[...]` even for one
  element). **Getting this wrong is a real, silent-until-runtime failure
  mode**, not just a style choice: writing a bare `enum(...)` where the
  real attribute is array-typed compiles fine and produces a classfile
  whose array-typed attribute got a scalar value instead — verified to
  reach all the way through to a `ClassCastException` inside Forge's own
  `@Mod.EventBusSubscriber` scanner (`EnumHolder` cast to `List`) the
  moment that class actually loads. There's no reflection against a real
  `@interface` to catch this at compile time (see below) — the source has
  to say which shape it means.
- Scoped to top-level `struct`/`fn` only for this first pass (not `impl`
  methods, interfaces, or enums) — that's exactly the shape Forge's
  event-bus pattern needs: an annotated `struct` gets the class-level
  annotation on its own generated class (`MyClientEvents` above compiles
  to a real `.class` with `@Mod.EventBusSubscriber` on it directly, not
  routed through any "first struct in the module" indirection — `selfhost/`
  has no such module-driven-class scheme to begin with), and an annotated
  top-level `fn` gets the method-level annotation on the real compiled
  static method wherever this compiler already puts top-level fns (the
  per-compile-unit synthesized driver class every `pub fn` becomes a
  static method on).
- Always emitted as `RUNTIME` retention (`RuntimeVisibleAnnotations`, not
  `RuntimeInvisibleAnnotations`) — reflection-driven consumers need it at
  runtime, and there's no source-level knob to ask for less.
- Same trust model as `extern`: the annotation's binary name and argument
  shape are never checked against a real `@interface` declaration (there
  isn't one to check against — declaring actual Java annotation *types*
  from HC isn't supported, only *using* existing ones). A typo'd name or
  wrong argument type compiles fine and just silently doesn't match what
  the reflecting framework expects — no compile-time or load-time error,
  since the JVM doesn't validate annotation data against the annotation
  interface at class-load time either.

#### `@serializable`: compiler-generated `encode`/`decode` for a struct

**Not implemented in `selfhost/` today** — see the "Java annotations"
note above; confirmed via `examples/test_serializable.hotc`, which
fails with `unexpected token '@'`.

A bare compiler *directive* (no quoted binary name — distinguished from
`@"binary.Name"(...)` above at parse time), immediately before a
top-level `struct`. Generates real `pub fn encode(packet: &S, buf: &mut
FriendlyByteBuf)` / `pub fn decode(buf: &mut FriendlyByteBuf) -> S`
top-level fns from the struct's own field list, in declaration order —
genuinely generated bytecode (`javap` shows real `encode`/`decode`
methods), not a runtime reflection scheme:

```
extern class FriendlyByteBuf = "net.minecraft.network.FriendlyByteBuf" {
    // Really returns `FriendlyByteBuf` itself (fluent chaining), not `void` -- get this wrong
    // and it compiles fine, then throws `NoSuchMethodError` the moment it's actually called (see
    // "extern class fields"/"Java interop" below for why: the JVM matches by exact descriptor,
    // return type included, and there's no reflection here to catch a wrong one at compile time).
    fn writeUtf(self, v: String) -> FriendlyByteBuf;
    fn readUtf(self) -> String;
    // ... one write/read pair per field type actually used below
}

@serializable
struct ClipboardPacket {
    data: String,
}

fn main() {
    let packet = ClipboardPacket { data: "hello" };
    var buf = FriendlyByteBuf::new();
    encode(&packet, &mut buf);
    let restored = decode(&mut buf);
}
```

- Scoped to exactly one target for this pass: an `extern class` literally
  named `FriendlyByteBuf` must be declared somewhere in the same compile
  (Forge's networking buffer type is the motivating case — see
  `kubejs-aisle-tool`'s `ClipboardPacket`, whose hand-written
  `encode`/`decode` this replaces). No pluggable backend (NBT/JSON/other
  wire formats) yet.
- Field types are limited to `Int`/`Long`/`Float`/`Double`/`Bool`/
  non-nullable `String`, each mapping to a real
  `writeInt`/`readInt`, `writeLong`/`readLong`, `writeFloat`/`readFloat`,
  `writeDouble`/`readDouble`, `writeBoolean`/`readBoolean`,
  `writeUtf`/`readUtf` call — a struct field of any other type (a nested
  struct, an enum, a nullable `String?`, another `extern class`) is a
  compile error naming the unsupported field.
- Whether the declared `FriendlyByteBuf` extern class actually has the
  needed `writeX`/`readX` methods is left to the normal checker pass over
  the synthesized bodies to catch — same "no special-cased validation"
  approach the `by field` interface-delegation synthesis already uses.
- `encode`/`decode` are ordinary top-level `pub fn`s (not name-mangled
  like `impl`-desugared methods), landing on the struct's own holder
  class via the "named binary target" mechanism above when the struct is
  its file's first declared one — so a compile with more than one
  `@serializable` struct needs each in its own file/module to avoid the
  same flat-global-namespace collision any other same-named top-level
  `pub fn` pair would hit.

### String interpolation: `"a {expr} b"`

```
struct Enemy { name: String, health: Int }

fn main() {
    let enemy = Enemy { name: "Goblin", health: 42 };
    print("Enemy {enemy.name} has {enemy.health} health");
}
```

- Sugar over the existing `String + String` concatenation, not a new value kind
  — `"a {x} b"` desugars (in the lexer) into literal segments and real embedded
  expressions, then compiles (in codegen) to the same `StringBuilder` chain
  `+` on strings already used, just generalized to N parts instead of 2. No
  new runtime mechanism.
- An embedded `{expr}` can be **any expression**, not just a bare variable —
  `"{1 + 2}"`, `"{enemy.get_name()}"`, `"{a} and {b}"` all work, because the
  lexer splices the expression's own real token stream into the surrounding
  string's tokens and the parser's ordinary `expression()` parses it — no
  separate mini-parser for interpolation.
- Only the existing `Copy` types can be interpolated: `Int`/`Long`/`Float`/
  `Double`/`Bool`/`String` — the same set `print()` already knows how to
  render. There's no user-defined `toString`/interpolation overload
  mechanism in this language, so interpolating a struct or `extern class`
  value is a **compile-time error** naming the offending type, not a silent
  JVM-default `toString()` or an unhelpful runtime crash.
- `\{`/`\}` escape to literal braces; `{}` (empty) is a legal, if useless,
  interpolation of nothing. Nested strings/interpolation inside a `{...}`
  work too (`"outer {"inner " + "concat"} done"`) — the lexer tracks brace
  depth and skips over nested string literals correctly rather than naively
  scanning for the next `"`. See `examples/interpolation.hc`.

### Nullable types: `Type?`

**Ported to `selfhost/`, fixed 2026-09-04** — see "Status" near the top of
this doc. **Corrects this section's own earlier text**, which described
the retired Kotlin compiler's version (`Ty.JavaExtern.nullable`,
`Checker.narrowNonNull`, `IFNULL`/`IFNONNULL` codegen) as if it had
already carried over into `selfhost/` — it hadn't; before this pass,
`Type?` failed to parse at all (`unexpected token '?'`) anywhere in
`selfhost/`, `null` had no `Expr` node to parse into, and there was no
narrowing mechanism of any kind. The real `selfhost/` design below is
similar in spirit but a genuinely different, independently-built
implementation, motivated directly by real Minecraft/Forge modding needs
(`UseOnContext.getPlayer()`-shaped APIs, nullable for a non-player use
context).

```
extern class SecurityManager = "java.lang.SecurityManager" {
    fn toString(&self) -> String;
}
extern class System = "java.lang.System" {
    static fn getSecurityManager() -> SecurityManager?;
}

fn describe_security_manager() -> String {
    let sm = System::getSecurityManager();
    if sm == null {
        return "no security manager installed";
    }
    // `sm` is narrowed to plain (non-nullable) SecurityManager here --
    // calling a method on it needs no cast or extra check.
    return sm.toString();
}
```

- **`Type?` marks a type as nullable** — a plain suffixed type-name
  string (`"SecurityManager?"`), the same "no new `Ty` variant, just a
  recognizable string shape" convention `[Name]`/`dyn Name` already use
  in this compiler — legal anywhere a type name is written (`Parser.hotc`'s
  own shared `type_name_ref`): extern method/field types, fn param/return
  types, struct field types. **Scoped to a struct/extern-class-shaped
  value this pass** (`TyStruct` in `Types.hotc`'s terms) — covers BOTH a
  plain HC struct (`Player?`) and an `extern class` type
  (`SecurityManager?`) equally, a genuinely WIDER scope than the retired
  compiler's own extern-only restriction above. Native `String?` is a
  real, disclosed follow-up, not covered yet — see below.
- **`null` is a real literal** (`Expr.NullLit`, a new `NULL` lexer
  keyword that already existed but was never wired into the parser) —
  checks to `TyUnknown`, the same "compatible with anything, no static
  check to run" escape hatch every other untyped-until-context shape
  already gets in this checker, so `x == null`, `return null;`, and
  passing `null` as an argument all just work with no special-casing
  needed anywhere else.
- **A still-nullable value used in a method call or field access is a
  real compile error** — *"possibly-null value of type '...' used
  without a null check"* — verified directly.
- **Guard-clause narrowing, two idioms**:
  - `if x == null { <block ending in return/throw> }`, with or without an
    `else` — narrows `x` non-null for every statement AFTER the whole
    `if`, in the same enclosing block (reachable only via a path where
    `x` was proven non-null); an `else` block, if present, is ALSO
    narrowed within itself (reaching it means the condition was false).
  - `if x != null { ... }` — narrows `x` non-null WITHIN that `then`
    block only, never leaking to an `else` or to code after the `if`.
  Recognized via a **3-token lookahead over the raw token stream**
  (`IDENT (EQEQ|BANGEQ) NULL`) done by `Parser.hotc`'s own `if_stmt`,
  BEFORE the condition itself is parsed — not by re-inspecting the built
  condition `Expr` downstream in the checker/codegen. This is a real,
  deliberate design choice, not an arbitrary implementation detail: `Expr`
  is move-only in this compiler, and the condition still has to reach
  `check_expr`/`gen_expr` completely unchanged either way, so there is no
  way to inspect its shape a SECOND time downstream without consuming the
  one copy that call needs. Two new fields on `Stmt.If` itself
  (`narrow_name`, `narrow_is_eq` — `""` when the shape wasn't recognized)
  carry the parser's own finding forward; `Checker.hotc`'s `check_stmt`
  and `Codegen.hotc`'s `gen_stmt` each read them directly (duplicated
  independently, per this compiler's usual checker/codegen split) rather
  than re-deriving anything.
- **A non-null value passed where a nullable one is expected is legal
  widening** (`Type` -> `Type?`, e.g. passing an already-narrowed local
  into a `Type?`-declared param) — the reverse direction still needs
  narrowing first.
- **Codegen** reuses the existing `==`/`!=` binary-op machinery
  (`IF_ACMPEQ`/`IF_ACMPNE` against a real `ACONST_NULL`) rather than a
  dedicated `IFNULL`/`IFNONNULL` opcode pair — a real, disclosed simpler
  implementation than the retired compiler's own, functionally
  equivalent for this purpose.

**Known limitations, real scope cuts, not oversights**:
- **Only the exact `ident == null`/`ident != null` shape narrows** (either
  literal order) — no `if x == null || y == null { ... }` compound
  conditions, no narrowing inside a `while` condition, no narrowing of
  anything other than a bare identifier (a field access like `mc.player`
  needs binding to a local first: `let player = mc.player; if player !=
  null { ... }`). Pattern-matched narrowly against the idioms real
  Forge-style modding code actually needs, not a general flow-typing
  system.
- **Native `String?`, fixed 2026-09-04** — `String?` is no longer scoped
  out. `System::getProperty(key) -> String?`-shaped extern signatures
  (`Map.get`, `System.getProperty`, ...) now work end to end, narrowing
  included. See "Native `String?`" below for the full design — `String`
  already had its own `+`/interpolation/native-method-bridge special
  cases throughout the checker and codegen, each of which needed its own
  "reject a not-yet-narrowed nullable String" gate, plus a null-safe
  `Objects.equals`-based `==`/`!=` codegen path (the naive `a.equals(b)`
  NPEs the moment `a` is genuinely null — a real, newly-reachable bug
  found and fixed along the way, since `null` becoming a real expression
  this session made `null == "literal"` expressible for the first time).
- **Struct-literal field widening isn't covered** — passing an
  already-narrowed (non-null) value into a nullable-declared STRUCT
  FIELD (`Holder { p: somePlayer }` where `p: Player?`) isn't recognized
  as legal widening the way a fn-call argument is; narrower than the
  "nullable used without narrowing" enforcement is currently.
- **No return-type checking against a nullable declared return type at
  all** — this checker doesn't validate `return` values against the
  enclosing fn's declared return type in ANY case, nullable or not (a
  real, separate, pre-existing gap, not something this pass introduced
  or could fix in scope).
- `read_line()` stays declared non-nullable for now, deliberately, even
  though the real `BufferedReader.readLine()` it wraps can genuinely
  return null at EOF (see `examples/input.hc`'s existing, honest-about-
  itself failure mode) — changing that would be a breaking change to
  every existing program calling `read_line()` without narrowing. Worth
  revisiting, just not silently as a side effect of this pass.

### The `?.`/`?:` operators

**Ported to `selfhost/`, fixed 2026-09-04** — see "Status" near the top
of this doc. Layers directly on top of `Type?` (above): reduces the
`if x == null { ... } else { x.method() }` guard-clause boilerplate to a
single expression for the common case.

```
fn find_player(has: Bool) -> Player? {
    if has { return Player { name: "Rin" }; }
    return null;
}

// `?.` -- short-circuits to null if the receiver is null (args aren't
// even evaluated), otherwise calls/reads through it, wrapping the
// result back to nullable.
let greeting = find_player(true)?.greet();   // "hi Rin"
print(find_player(false)?.greet() == null);  // true

// `?:` -- the left value if non-null, else the right (default) value.
let guest = Player { name: "Guest" };
print((find_player(false) ?: guest).greet()); // "hi Guest"
print((find_player(true) ?: guest).greet());  // "hi Rin"
```

- **`x?.method(args)` / `x?.field`** — parsed inside the ordinary postfix
  chain (`Parser.hotc`'s own `postfix_loop`, alongside plain `.`), not as
  a special case: a `QUESTION` immediately followed by `DOT` triggers it,
  same 1-token-lookahead discipline as everywhere else in this parser.
  `recv` is evaluated exactly once. **Scoped to a struct/extern-class
  result type** — same restriction `Type?` itself has (no boxed nullable
  primitive), so `x?.someIntField` is a real compile error naming the
  problem, not a silent wrong answer.
- **`x ?: default`** — a new precedence tier between `assignment` and
  `logical_or` (so `a ?: b || c` parses as `a ?: (b || c)`), right-
  associative. `left` must genuinely be nullable; `default`'s type must
  match `left`'s underlying (non-null) type, same widening
  `is_arg_type_mismatch` already applies to an ordinary call argument.
- **A genuine grammar conflict, resolved**: `?` alone is ALSO the `?`
  (try) operator's own postfix trigger (`expr?`, see "The `?` operator"
  below) — `x ?: y` and `x?.y` both start with the exact same token the
  try-operator postfix loop would otherwise eat. `?.`  is consumed first,
  inside `postfix_loop` itself (before the try-postfix loop even runs);
  `?:` needs an explicit 1-token lookahead in the try-postfix loop
  (`QUESTION` not immediately followed by `COLON`) so `x ?: y` doesn't
  get wrongly parsed as `TryOp{x}` followed by a dangling `: y`.
- **Real bug found running this for real, fixed**: `x?.method()`/
  `x?.field`'s own codegen originally left the DUP'd, still-typed
  receiver reference (e.g. `Player`) on the stack for the null path,
  while the non-null path leaves the member's own (generally DIFFERENT)
  result type (e.g. `String`) — forcing ASM's `COMPUTE_FRAMES` to compute
  a real common supertype for the two at the merge point. Its default
  algorithm does that via `Class.forName` against the actual JVM
  classpath, which fails outright for a struct still being compiled IN
  THE SAME PASS (`ClassNotFoundException: Player`, not a bug in the
  struct itself — verified directly, a real crash before this fix).
  Fixed by discarding that typed null and pushing a fresh, untyped
  `ACONST_NULL` instead on the null path — the verifier's "null type"
  merges with any reference type for free, no reflection involved at
  all.
- Verified end to end (`examples/safe_nav.hc`): `?.` on both an absent
  and a present receiver (confirming the short-circuit genuinely skips
  evaluating the call), `?:` on both sides, a parenthesized `?:` result
  immediately chained with `.method()`, and both designed error cases
  (`?.` on a non-nullable value, `?.` whose result would be a primitive).

### Native `String?`

**Ported to `selfhost/`, fixed 2026-09-04** — see "Status" near the top
of this doc. `Type?` (above) already covered a struct/extern-class value;
this closes the last disclosed gap by extending the SAME nullability to
native `String`, the one built-in type with its own special-cased
operations (`+`, string interpolation, the native-`.method()` bridge)
rather than a plain method/field table.

```
extern class System = "java.lang.System" {
    static fn getProperty(key: String) -> String?;
}

fn describe_property(key: String) -> String {
    let v = System::getProperty(key);
    if v == null {
        return "no such property";
    }
    // `v` is narrowed to non-nullable String here -- `+`, `.method()`,
    // and interpolation all work with no cast/workaround needed.
    return "found: " + v.trim();
}
```

- **Guard-clause narrowing already covered this for free** — the
  narrowing mechanism (`Ast.hc`'s own `Stmt.If.narrow_name`) never
  special-cased what KIND of nullable value it was narrowing; it only
  ever checked `is_nullable_type_name` on the var's own type-name string,
  so `String?` narrows exactly like `Player?` does, with zero changes to
  that mechanism at all. Likewise `check_method_call`/
  `check_field_access_by_type`'s own "possibly-null value used without a
  null check" enforcement already applied uniformly — a still-nullable
  `String?` calling `.trim()` was already rejected correctly before this
  pass, since neither check ever special-cased "struct" vs "String".
- **What genuinely needed its own gate**: `+` (string concatenation) and
  string interpolation both had their OWN exact-match `ln == "String"`
  checks that a `"String?"`-suffixed name simply never matched — falling
  through to a confusing, unrelated error (`+`'s own "needs matching Int,
  Long, Float, or Double operands") instead of a clear "possibly-null"
  one. Both (`Checker.hotc`'s own `check_binary`/`check_string_interp`)
  now recognize a nullable-suffixed `String` explicitly and give the
  same clear error the struct/extern case already had, then treat it as
  narrowed so the ordinary case right below still resolves normally.
- **A real, newly-reachable NPE found and fixed**: `==`/`!=`'s own
  codegen special-cased `String` the same exact-match way — comparing
  `null == "literal"` (`null`'s own type tag is `"?"`, `"literal"`'s is
  `"String"`) still matched via the literal side, then called
  `INVOKEVIRTUAL Object.equals` with `null` as the RECEIVER —
  `null.equals(...)`, a guaranteed `NullPointerException` at runtime, not
  a compile error. Latent since before this whole nullable-types pass
  (`null` had no `Expr` to parse into at all until then), but newly
  REACHABLE the moment `null` became a real literal. Fixed by widening
  the check to "either side is String-shaped, nullable or not" and
  routing anything that isn't a PROVABLY non-null `String` receiver
  through `Objects.equals(a, b)` instead (null-safe on either argument)
  — the cheaper direct `a.equals(b)` stays only when the left operand's
  own type tag is exactly `"String"` (a literal, or an already-narrowed
  local).
- Verified end to end (`examples/nullable.hc`, extended): a real
  `System::getProperty` call narrowed then used with `+`/`.trim()`, the
  missing-key `else` path, `?.`/`?:` on a native `String?`, and a
  null-safe `==` comparison against BOTH `null` and a string literal on
  an unnarrowed value (confirming no NPE), plus both designed error
  cases (`+` and interpolation on an unnarrowed `String?`).

### `extends`: subclassing a real Java class

**Ported to `selfhost/`, fixed 2026-09-03** — see "Status" near the top
of this doc. Verified end to end, including `javap`-confirmed bytecode.

The actual last-mile feature for writing a whole Forge/Minecraft mod class in
Hot Chocolate, not just its logic. Everywhere else in this language, "IS-A"
is `impl Interface for Struct` (JVM `implements`) — composition over
inheritance, deliberately. But some Java frameworks (Forge chief among them)
require genuine *subclassing* to hook into: `Item`, `Block`, `BlockEntity`,
`Screen` all expect you to extend them, not just implement an interface,
because their default behavior lives in the superclass and gets reached via
`super.foo()`. `extends` is the escape hatch for exactly that case, and only
that case — it doesn't change the interface-based story for everything else.

```
extern class ArrayList = "java.util.ArrayList" {
    fn new() -> Self;
    fn size(&self) -> Int;
}

struct LoudList extends ArrayList {}

impl LoudList {
    override fn size(&self) -> Int {
        print("size() called!");
        return 42;
    }
}

fn main() {
    let list = LoudList::new();
    print(list.size());  // prints "size() called!" then 42
}
```

- **`struct S extends C { }`**: `C` must be a previously-declared `extern
  class`. `S`'s generated classfile has `C`'s real binary name as its actual
  JVM `superName` — verified with `javap`: `final class LoudList extends
  java.util.ArrayList`, not a comment claiming so.
- **Construction**: `S::new(args)` — same `::new` spelling as an extern
  class's own constructor call, deliberately, since from the caller's side
  it's the same shape. One generated `<init>` per constructor `C` itself
  declares, each just loading its args and forwarding straight to `C`'s real
  `<init>` via `INVOKESPECIAL` — verified in the compiled bytecode.
- **`impl S { override fn method(&self, ...) { ... } }`**: matched by name
  against `C`'s declared method table and validated against its *exact*
  signature (self + params + return), same rigor as an interface override.
  Compiles to a real instance method with `C`'s own method name (not this
  language's usual mangled/static-desugared scheme), so external Java code
  holding a plain `C` reference reaches it through ordinary JVM virtual
  dispatch — verified by actually calling `.size()` on a `LoudList` and
  confirming the override runs, not just that it compiles. `self.method(...)`
  from other HC code on the same struct also resolves to it, through the
  same real-instance-method/`INVOKEVIRTUAL` path an interface override
  already uses (`checkMethodCall`'s `Ty.Struct` branch treats a superclass
  override and an interface override as the same kind of explicit override).

**Known limitations, real scope cuts for this pass, not oversights**:
- **`S` must have zero fields of its own.** The generated constructor's only
  job is forwarding args to `C`'s constructor — there's nowhere for extra
  per-instance state to live in that scheme yet. A struct needing both
  inherited behavior *and* its own fields isn't supported.
- **No `super.method(...)` calls.** An override can't invoke the base
  class's own implementation of the method it's overriding — it's a full
  replacement, not an augmentation.
- **Single inheritance only, no further subclassing.** `S` itself is
  generated `final`; nothing can extend an `extends`-struct.
- **Resolved**: overriding a method whose body needs a null check (Forge's
  `Item.useOn(UseOnContext)` calling `context.getPlayer()`, which is
  legitimately null for a non-player use context like a dispenser) used to
  be blocked here, before "Nullable extern references: `Type?`" shipped.
  The real mod's `CopyToolItem` is now fully off its Java shell — see
  `kubejs-aisle-tool/hc/CopyToolItem.hc`, a real `extends Item` struct
  overriding `use`/`useOn`/`appendHoverText`, verified end-to-end against
  the real Forge classpath (`javap` confirms the generated class really
  `extends net.minecraft.world.item.Item`, and the full mod build links
  against it).

### Logical operators: `&&` / `||`

```
while i < arr.length && arr[i] < 10 {
    print(arr[i]);
    i = i + 1;
}
```

Real short-circuit evaluation (branch-based codegen, not eager-evaluate-
both-sides-then-combine) — `||` binds looser than `&&`, which binds
looser than `==`/comparisons, same relative precedence as every
C-descended language. The short-circuiting isn't just a performance
nicety: `arr[i]` above must never execute once `i < arr.length` is
false, or the loop would throw `ArrayIndexOutOfBoundsException` on its
last iteration. Verified both for short-circuit *behavior* (a right-hand
side with an observable side effect, like a `print`, genuinely never
runs once the left side alone decides the result) and for *correctness
under that reliance* (the bounds-check-then-index pattern above runs
clean with no exception). See `examples/logical_ops.hc`.

### `String` can call `extern class` methods declared against `"java.lang.String"`

```
extern class JString = "java.lang.String" {
    fn split(&self, regex: String) -> [String];
    fn trim(&self) -> String;
}

fn main() {
    let s = "  10 20 30  ";
    print(s.trim().split(" ")[0]);
}
```

**Corrects an earlier audit note in this doc, verified 2026-09-04 by
actually running `examples/string_bridge.hc` and reading the real
codegen path**: this section used to describe the mechanism as "the
checker treats a `Str_`-typed value as free to call any method some
`extern class` happened to declare against that binary name, with zero
codegen changes" — a leftover claim from the retired Kotlin compiler's
own design, never re-verified against `selfhost/`. The real
`selfhost/` mechanism is different and much less precise: `check_method_call`
resolves the receiver's type name to plain `"String"` (not the
user's `extern class` alias, e.g. `JString`), so its lookup into
`self.extern_methods` — keyed by the DECLARED alias name — never
matches at all. The checker instead falls all the way through to its
own generic "no method table for this receiver type, trust it" case
(the same escape hatch a genuine typo on ANY type gets), returning
`TyUnknown` for `.trim()`/`.split()`/etc. with **no real signature or
return-type checking whatsoever** — the `extern class JString = ...`
declaration in the example above doesn't actually do anything; the
program would compile identically without it. All of the real work
happens in `Codegen.hotc`'s own hardcoded fallback dispatch table for
native-`String` methods (`length`, `trim`, `substring`, `split`,
`isEmpty`, ...) — it alone determines each method's real JVM signature
and HC return type, and a method missing from that table (as `split`/
`isEmpty` were until fixed — see "Status" above) fails at codegen time
with "not implemented," not at check time. This is the actual bridge
from plain string literals/locals to real `.split()`/`.trim()`/
`.substring()`/etc. — before this, a native `String` had no methods at
all, and the only workaround (see `hc/Copytool.hc`'s alphabet array)
was avoiding string methods entirely. See `examples/string_bridge.hc`.

**Known trap, found the hard way — avoid `String.charAt(int)`:** it
really returns Java `char` (JVM descriptor `C`), and HC has no `Char`
type to declare that return as. Declaring it `-> Int` (descriptor `I`)
type-checks fine and **crashes at runtime** with `NoSuchMethodError:
'int java.lang.String.charAt(int)'` the instant it's actually called —
verified directly. Use `.substring(i, i + 1)` for a single character
instead: real `String` in, real `String` out, no descriptor mismatch
possible. This is the same class of trap as the pre-existing "generic
Java APIs erase to `Object`" limitation below (`Map.put`/`.get`/
iterator methods all really return `Object`, not whatever type you
`extern`-declared) — a wrong-but-type-checking `extern` signature is
always a live risk exactly where the real JVM signature doesn't match
Java source-level intuition. When in doubt, verify by actually running
the call, not just compiling it — the checker trusts every `extern`
declaration completely and has no way to catch this itself.

### Error handling: `try`/`catch`/`throw`, and `Result<T, E>`

**Fixed 2026-09-03**: multiple `catch` clauses on one `try` — the `Try`
AST node used to have room for only one `catch_var`/`catch_type`/
`catch_block`. Now a real `Vec<CatchClause>` (one or more); codegen
registers one real `visitTryCatchBlock` exception-table entry per
clause, all covering the same `try` range, in declaration order —
matching the JVM's own "first matching handler wins" resolution, so an
earlier, narrower `catch` is correctly preferred over a later, broader
one. Verified with both a thrown-and-caught-by-the-second-clause case
and a thrown-and-caught-by-the-first-clause case in the same program —
each correctly skips the non-matching clause. **Fixed 2026-09-03**:
`Result<Int, String>` — a two-type-parameter generic enum — used to
fail to parse at all; `examples/error_handling.hc`'s own `safe_parse`
(a real `try`/`catch` returning `Result<Int, String>`) now compiles and
runs correctly end to end, verified together with the multi-clause
`catch` fix above in the same program. See "Two-type-parameter
generics" below for the full design. **Fixed 2026-09-04**: the `?`
operator on `Result` — see "The `?` operator" section right below.

Two separate, deliberately non-overlapping mechanisms — real JVM exceptions
for the Java interop boundary, and an ordinary enum for HC-native
fallibility:

```
extern class Integer = "java.lang.Integer" {
    static fn parseInt(s: String) -> Int;
}
extern class NumberFormatException = "java.lang.NumberFormatException" {
    fn getMessage(&self) -> String;
}

fn parse_or_default(s: String, default: Int) -> Int {
    try {
        let n = Integer::parseInt(s);
        return n;
    } catch (e: NumberFormatException) {
        print("caught: " + e.getMessage());
        return default;
    }
}
```

- **`try { } catch (e: SomeExternType) { } catch (e2: OtherExternType) { }`**
  compiles to real JVM exception handling — ASM `visitTryCatchBlock`
  entries against the try body's start/end labels, one per `catch` clause,
  each with its own handler label and the caught type's real binary name.
  Not a language-invented mechanism: this is exactly what `javac` itself
  emits for a multi-catch `try`, just without checked-exception tracking.
- A `catch` type must resolve to an `extern class` (a real, trusted JVM
  type) — there's no HC-native exception type, and nothing here verifies
  the declared type is actually a `Throwable` subclass beyond the usual
  "trust the `extern` declaration" scope cut every other `extern`
  interaction already has. Multiple `catch` clauses are checked against
  the thrown value's runtime type in declaration order, same as Java.
- **`throw someExternValue;`** compiles directly to `ATHROW`. `someExternValue`
  must check to an extern class type (checker-enforced), same "trust it's
  really a `Throwable`" scope cut as `catch`.
- **Move-checking through `try`/`catch`**: a thrown exception can interrupt
  the try block at *any* point, so each `catch` body is checked from the
  moved-state *before* the try block ran — not after — since assuming the
  try block's own code fully executed before checking a handler for its
  own failure would be unsound. The statement's overall moved-state
  afterward conservatively merges the try block's outcome with every
  catch's, the same OR-merge `if`/`else` already uses for its two branches
  generalized to N branches.
- **Known limitation**: no `finally` yet — cleanup on the exceptional path
  needs a `catch` that re-`throw`s after doing the cleanup, for now.

```
pub enum Result<T, E> {
    Ok { value: T },
    Err { error: E },
}
```

- A small addition to the [self-hosted prelude](#vect-and-registryt----a-small-self-hosted-prelude),
  written in Hot Chocolate itself exactly like `Option<T>`/`Vec<T>` — for
  representing "this **HC-native** operation can fail" as an ordinary
  matchable value (no exception, no special control flow), the same way
  `Option<T>` already represents "this can be absent." `try`/`catch` and
  `Result` compose naturally: catch a real Java exception, then return an
  `Err` instead of propagating it further, turning "this specific JVM call
  can throw" into "this function's result might be absent," at the exact
  boundary where that translation makes sense — see `safe_parse` in
  `examples/error_handling.hc`.
- Building this surfaced and fixed a **real, previously-dormant checker
  bug**: the "infer a still-unsolved generic type param from the
  expected/declared type" fallback (originally built only for zero-field
  variant construction like bare `None`) was reading from the wrong
  internal map — `structInstanceArgs` (keyed by monomorphized *struct*
  names) instead of `enumInstanceArgs` (the actual *enum* one). This never
  mattered before because bare `None`/unit-variant construction goes
  through a completely different checker path (`Expr.Ident`) that never
  touches that fallback at all — it only surfaced once a *non-zero-field*
  variant needed a still-unsolved param filled from context, exactly
  `Result::Ok { value: n }`'s situation (`T` infers from `value`, but `E`
  never appears in an `Ok` at all, so it can only come from the function's
  declared return type). Fixed by pointing the fallback at the right map,
  and generalizing it from "all-or-nothing" (only fires when zero params
  are solvable from fields) to "fill whichever specific params are still
  missing after fields," so it now covers both cases with one code path.

### The `?` operator: unwrapping `Result<T, E>` with early return

**Fixed 2026-09-04** — see "Status" near the top of this doc. Verified
via a real two-level `?` chain and all three documented error cases
(wrong operand type, wrong enclosing-fn return type, mismatched error
type).

```
fn safe_parse(s: String) -> Result<Int, String> {
    try {
        let n = Integer::parseInt(s);
        return Result<Int, String>::Ok { value: n };
    } catch (e: NumberFormatException) {
        return Result<Int, String>::Err { error: "bad number: " + s };
    }
}

fn parse_positive(s: String) -> Result<Int, String> {
    let n = safe_parse(s)?;             // unwraps Ok, or early-returns Err
    if n <= 0 {
        return Result<Int, String>::Err { error: "not positive" };
    }
    return Result<Int, String>::Ok { value: n };
}
```

`expr?` desugars to: unwrap `Ok { value }` to `value`, or construct and
early-`return` a fresh `Err { error }` for the ENCLOSING fn's own
`Result` return type. `expr`'s own `T` doesn't have to match the
enclosing fn's `T` (a chain of `?`s can narrow/transform the success
value at each step, exactly like `parse_positive` re-wrapping
`safe_parse`'s own `Int` above) — only the two `E`s have to match
exactly, checked with no implicit conversion (no Rust-style `From`
this pass).

- **Only legal inside a fn whose own return type is `Result<T, E>`** —
  `Checker.hotc`'s own `check_try_op` reads `self.cur_ret_ty` (already
  tracked for `Return`/return-position generic inference, no new
  tracking needed) and rejects `?` used anywhere else with a clear
  error naming the actual return type.
- **The operand must genuinely be `Result<_, _>`** — a bare `Option<T>`
  or any other type is rejected outright, not silently duck-typed.
- **The error types must match exactly** — `expr?`'s own `E` compared
  against the enclosing fn's declared `E`, both by name; a mismatch is
  a real error naming both.
- **No new runtime mechanism** — `Result`'s actual `selfhost/` codegen
  representation is the same single-tagged-class-plus-mangled-fields
  shape every other enum in this compiler already uses (see "Generics"
  above), so `gen_try_op` just reads the `tag` field and branches,
  exactly like `match`'s own codegen already does for an ordinary
  enum — narrowed to `Result`'s two known variants instead of a
  general arm list — then reuses the SAME `gen_variant_construct`
  helper ordinary `Err { error: ... }` construction already goes
  through to build the early-returned value.
- The AST node is named `Expr.TryOp`, not the more obvious `Expr.Try` —
  a real naming collision with the pre-existing `Stmt.Try` (the
  `try`/`catch` statement): this compiler's own variant-name registry
  is a single flat namespace across every `enum` in a compile unit, not
  scoped per-enum, so two different `enum`s can't share a bare variant
  name without one silently clobbering the other's registration.

### Named arguments

**Fixed 2026-09-04** — see "Status" near the top of this doc. Scoped to
calls to a plain top-level `fn` only.

```
fn createEnemy(name: String, health: Int, damage: Int) -> String {
    return "{name} hp={health} dmg={damage}";
}

fn main() {
    // any order -- resolved to the DECLARED order, not call-site order
    let e = createEnemy(health: 50, damage: 5, name: "Skeleton");
    print(e);  // "Skeleton hp=50 dmg=5"
}
```

`name: value` in a call's argument list is recognized by a bounded
1-token lookahead in `Parser.hotc`'s own `one_arg` — `IDENT` immediately
followed by `COLON` — which no legal expression production in this
grammar otherwise starts with, so it can't misfire on an ordinary
positional argument that happens to start with an identifier (`x`,
`x + 1`, `foo()`, ...). Each named argument parses to a new
`Expr.NamedArg { name, value }` node that only ever appears inside a
`Call`/`MethodCall`/`StaticCall`'s own `args` list.

- **Reordered to positional order up front** — `Checker.hotc`'s
  `resolve_named_args_chk` and `Codegen.hotc`'s `resolve_named_args_cg`
  both run at the very start of `check_call`/`gen_expr`'s own `Call`
  handling, before anything else looks at `args`: they look up the
  callee's declared param names (a new `fn_param_names: Registry<Vec<String>>`
  in each file, populated in `build_codegen`/`compile_program` the same
  way `fn_param_types`/`fn_sigs` already are — this compiler's checker
  and codegen don't share registries, so the lookup is genuinely
  duplicated, not refactored into one), then build a fresh `Vec<Expr>`
  with each `NamedArg`'s `value` moved into its declared slot. Everything
  downstream of that point sees an ordinary positional arg list and
  needs no changes at all.
- **All-or-nothing** — mixing named and positional arguments in one call
  is a hard error (`mixing named and positional arguments isn't
  supported in a call to '...'`), not silently allowed with named args
  filling the gaps.
- **Plain top-level `fn` calls only, this pass** — a named argument on a
  `MethodCall`, `StaticCall`, or extern call is rejected
  (`named argument '...' isn't supported here`), a disclosed scope cut:
  `fn_param_names` is only ever populated for plain top-level fns, so
  there's nowhere for a method/static/extern call to look param names up
  from yet.
- Verified: named args in declaration order, named args given out of
  order (genuinely reordered, not just forwarded positionally —
  `add(b: 3, a: 10)` against `fn add(a: Int, b: Int) -> Int { return a
  - b; }` correctly returns `7`, not `-7`), a positional call to the
  same fn still working unchanged, and both designed error cases (mixed
  named/positional, named args on a method call).

### User input: `read_line()`

A built-in, special-cased in the checker/codegen exactly like `print` —
takes no arguments, returns one line read from stdin as a `String`:

```
fn main() {
    print("What's your name?");
    let name = read_line();
    print("Hello, " + name + "!");
}
```

Backed by a single `BufferedReader` over `System.in`, created once in
the compiled program's own `<clinit>` and kept for the program's whole
lifetime (a fresh reader per call would silently drop any input the
previous one had already buffered ahead past the current line). Parsing
a line into something other than a `String` — an `Int`, say — isn't a
separate builtin; it's just an ordinary [Java interop](#java-interop)
call, e.g. `extern class Integer = "java.lang.Integer" { fn parseInt(s:
String) -> Int; }` then `Integer::parseInt(read_line())`. See
`examples/input.hc`.

**Known limitation**: at end-of-input, `readLine()` returns Java's
`null` — a value this language has no concept of (there's no
`Option<String>` wrapping here, unlike `Registry.get`). Reading past
EOF today just crashes at runtime the moment that `null` reaches
something that dereferences it (e.g. `String` concatenation prints the
literal text `"null"`, but `Integer::parseInt` throws a
`NumberFormatException`) — there's no compile-time or clean runtime
signal for "no more input" yet.

### JDK 17 compatibility (and why it matters for Minecraft modding)

**A program that never uses `arena struct` compiles to plain JDK
17-bytecode with zero `java.lang.foreign` references anywhere** — that
codegen path only runs when the AST actually contains an `arena`
allocation or an off-heap field access, so if you don't write `arena`,
nothing about it exists in the output. Verified with `javap -verbose`:
class files emitted for `hello.hc`/`generics.hc`/`methods.hc`/`mut.hc`/
`arrays.hc` all report `major version: 61`, which *is* Java 17 — not
"17 or newer," the literal class file version Java 17 itself emits.

This matters concretely for the eventual goal of writing a Minecraft
1.20.1 mod in Hot Chocolate: Minecraft 1.20.1 (Forge/Fabric) runs on
Java 17 specifically, so mod code has to load in a Java 17 JVM. As long
as the mod-side code sticks to normal structs/generics/methods/arrays —
i.e., skips `arena struct` — the `.class` files `hc build` produces
should be loadable there with no toolchain mismatch. (Actually wiring up
a Forge/Fabric mod-dev pipeline — packaging, `mods.toml`, the mod loader
APIs, a Gradle mod-dev plugin — is a separate, substantial piece of work
this repo doesn't attempt yet; this is just the language/compiler half
of that being unblocked.)

### Multi-file projects

**Fixed 2026-09-02** (source: `selfhost/Driver.hotc`'s
`collect_hotc_files`): directory mode only ever matched the `.hotc`
extension (used by this compiler's own source under `selfhost/`),
never plain `.hc` (used by every real example and mod project) — so it
silently collected zero files for any real `.hc` project, which
downstream surfaced as a confusing `NoSuchFileException: __init__`
(reading back a placeholder sentinel instead of a real path) rather
than any error actually naming the real cause. Now accepts both
extensions. `./gradlew run --args="run examples/multifile"` verified
working correctly, including a subdirectory-nested tree.

(An initial investigation session mistakenly suspected a second, deeper
bug in the directory walker's recursive merge — that turned out to be
a test-harness error: `SelfhostCLI`'s CLI has no `"build"` subcommand,
only `"run"`; passing `build <dir> <out>` treats the literal string
`"build"` as the target path, which happens to resolve to Gradle's own
`build/` output directory. Once invoked correctly, directory mode
including subdirectory recursion works fine.)

`hc run`/`hc build` accept a directory instead of a single `.hc` file:

```bash
./gradlew run --args="run examples/multifile"
```

Every `.hc` file *anywhere under* the directory — recursively, nested
subdirectories included — is parsed and merged into one flat program —
the exact same merge the prelude already goes through — before checking
and codegen. There's no `use`/`import` statement: every top-level name
(struct, fn, interface, enum, `extern class`) shares one global
namespace across every file in the tree, so a name can only be declared
once total, same as within a single file today. See `examples/
multifile/` (a `sealed interface` in one file, implemented by structs
in two others, matched over from a fourth) for a working example.

Nesting files under subdirectories mirroring their own `module` path
(`client/Foo.hc` declaring `module ...client;`, matching the Java/
Kotlin source-root convention) is a real, supported organizational
choice — a real Minecraft mod project's own `.hc` files are laid out
exactly this way — but it's purely a convenience for humans/tooling
browsing the source tree; the compiler itself never checks a file's
declared `module` against its actual directory path (see `IDEAS.md`'s
"Verify a declared module matches the file's directory path" for the
opt-in check that would add that, deliberately not built by default).
A perfectly flat directory with every file's module declared explicitly
works exactly as well — nesting changes nothing about how names resolve
or which class each file's own declarations land on.

This is also what makes `sealed interface`'s "every implementer is
declared in this compilation unit" guarantee actually mean something —
previously the entire program was always one file, so it was trivially
true; now it spans a real multi-file project directory (or tree).

**Known limitation**: no explicit dependency graph — every file under
the directory is compiled together regardless of whether anything in it
is actually used. Per-file namespacing, though, is exactly what `module`
(below) gives you.

### Modules: `module`, `pub`, `open`, `extend`

**Fully implemented in `selfhost/` now, `open`/`extend` fixed
2026-09-03** — see "Status" near the top of this doc. Verified end to
end via `examples/modules/` (directory-mode, two separate `module`s):
a struct-direct extension, an open-interface extension reached both
directly on the concrete struct and through `&dyn Machine`, and a
negative test confirming `extend`ing a non-`open` interface is a real
compile error.

Not a copy of Java's `package` — a real ownership/visibility boundary,
and the vehicle for the language's actual ethos: composition over
inheritance, and an *ecosystem* other code can extend without touching
the original source (the concrete case this was built for: a Minecraft
mod adding new behavior to another mod's types).

```
module minecraft.machine;

pub open interface Machine {
    fn name(&self) -> String;
}

pub struct Furnace { }
impl Machine for Furnace {
    fn name(&self) -> String { return "furnace"; }
}

struct InternalConfig { }   // no `pub` -- invisible outside this module
```

```
module phoenix.machines;

// Adds a brand-new method to Machine from a *different* module than the
// one that declared it -- no edit to machines.hc needed, only possible
// because Machine was declared `open`.
extend Machine {
    fn phoenixTier(&self) -> Int { return 1; }
}

// Extensions work directly on concrete structs too, not just open traits.
extend Furnace {
    fn describe(&self) -> String { return self.name() + ", a phoenix machine"; }
}
```

```
fn main() {
    let f = Furnace { };
    print(f.describe());     // struct-direct extension
    print(f.phoenixTier());  // trait extension, reached through Furnace's own impl
}
```

- **`module a.b.c;`** — optional, first thing in a file. Unlike a bare
  namespace prefix, different files in the *same* compile can freely
  declare different modules; each top-level struct/interface/enum is
  qualified into its own declaring file's module, independently. A
  declaration with no `module` line lands in the default (unnamed)
  module, exactly like a program that never declares one always has. Harmless for a standalone `hc run`/`hc build` script, but if that
  class is ever loaded by a real JPMS module system (e.g. embedded in a
  Forge mod, which runs under a `ModuleClassLoader`), an unqualified
  default-package class fails to load at all -- give every file meant to
  be consumed that way a real `module` line.
- **`pub`** — Rust-style, not Java's four-tier system: private by
  default, `pub` to export. For struct/interface/enum, this is real JVM
  enforcement, not just a checker opinion — a non-`pub` declaration
  compiles to a package-private class, so cross-module code that
  shouldn't reach it can't even link against it, `IllegalAccessError` at
  load time if something tries. (See `examples/modules/`.)
  **Regression found AND fixed, both 2026-09-23.** `Parser.hotc`'s own
  top-level parse loop used to call `self.match_kind(PUB)` and discard
  the result — no field anywhere recorded it, and `Codegen.hotc`'s own
  `gen_struct`/`gen_interface`/`gen_enum` unconditionally emitted
  `ACC_PUBLIC` on every class regardless — there was no package-private
  codegen path at all in the self-hosted port, and `InternalConfig` in
  this section's own first example was NOT actually invisible outside
  its module despite what its comment claimed. Real fix, same day: `pub`
  is now captured (`Program.pub_struct_names`/`pub_enum_names`/
  `pub_interface_names`/`pub_fn_names`/`pub_static_names`, the same
  side-channel shape `dev_fn_names`/`private_type_names` already use),
  and `Codegen.hotc` now defaults every struct/interface/enum class and
  every top-level fn/static class MEMBER to package-private, adding
  `ACC_PUBLIC` back only for a name actually in its own list — a real
  default-behavior FLIP, not just an addition. **Real, sweeping fallout
  found immediately**: NOT ONE declaration anywhere in `stdlib/*.hotc`
  used `pub` (nobody had a reason to, since it was a no-op) — every
  stdlib type/fn (`Option`, `Result`, `Vec`, `Registry`, `HashMap`, ...)
  would have gone package-private in the default package, breaking
  every consumer that ALSO declares a real `module` line (i.e. every
  real Forge/Fabric mod — exactly the intended, motivating use case for
  `module` at all). The self-hosted compiler's OWN 6 source files hit
  the identical problem cross-FILE (each declares its own `module
  hc.selfhost.*`, and plenty of small cross-file helper fns had never
  been marked `pub` either, having never needed to be before). Fixed
  both the same way: every top-level `struct`/`enum`/`interface`/`fn`/
  `static` in `stdlib/*.hotc` AND across all 6 `selfhost/*.hotc` files
  now has `pub` (mechanical, low-risk — adding `pub` only ever WIDENS
  access, never narrows it, so this could only fix breakage, never
  cause new breakage). Verified with a real `javap` run this time
  (`InternalConfig` now genuinely `class minecraft.machine.
  InternalConfig` — no `public` — vs. `Furnace`'s `public class`).
  Full example regression suite: zero new failures. Self-hosting
  verified to a true fixed point. `priv` (below) remains a separate,
  differently-scoped mechanism — a compile-time, name-based check for
  TYPES across FILES in a directory-mode compile, not JVM access
  control — this fix doesn't replace it.
- **`open interface X`** — marks a trait as allowed to receive new
  methods from `extend` blocks in other modules. Orthogonal to `sealed`:
  `sealed` is about the closed set of *implementers* (what `match`
  exhaustiveness needs); `open` is about the extensible set of
  *extension methods*, which never changes who implements the trait.
- **`extend Target { fn new(&self, ...) -> T { body } }`** — adds a
  callable method to an existing struct or `open interface`, from any
  module, without modifying `Target`'s own declaration. Every method
  needs a body (there's no implementer to ask for an override — their
  classes are already compiled); a real inherent/interface method always
  wins over a same-named extension — verified, `check_method_call` tries
  a real struct/interface method first, only falling back to a matching
  `extend` fn when that lookup genuinely finds nothing. **Not currently
  enforced in `selfhost/`**: two modules extending the same target with
  the same method name in scope together — a real ambiguity the design
  calls for rejecting, but this pass doesn't check for it; whichever
  one's top-level fn happens to register last in the compile unit
  silently wins, same as any other duplicate-top-level-fn-name collision
  already goes unchecked in this compiler. Under the hood this is genuinely simple: it desugars into an
  ordinary top-level function taking the receiver as an explicit first
  argument, resolved by the checker as a last-resort fallback after
  every normal method-lookup path has already failed — no new runtime
  mechanism, no vtable patching.

Top-level functions get real enforcement too, not just types: every fn
(including desugared impl/extend methods) lands on its own holder class
in its own package — a `pub fn` in one file is a genuinely separate,
independently-linkable JVM method from a same-named private one in
another, not two methods sharing a class. `fn main()`'s own file (or
the default/unnamed module, if it never declared one) keeps the
CLI-supplied class name and the real JVM entry point. Every other file
gets a **named binary target**: if it declares at least one `struct`,
its top-level `pub fn`/`pub static` land as ordinary static members
directly on *that file's own first* declared struct's class — a real
name Java code can reference by writing it, not a hidden one. Only a
file with no struct at all falls back to an internal `$Fns` holder.
**Same regression, same fix, both 2026-09-23** (see the `pub` bullet
above for the full story): a top-level fn/static's own `pub`-ness is
now real too (`Program.pub_fn_names`/`pub_static_names`), and a name
not in its own list lands on its holder class as a package-private
member (`ACC_STATIC` alone) instead of the unconditional `ACC_PUBLIC |
ACC_STATIC` every one used to get regardless — gated at the exact call
sites `gen_fn`'s own new `access: Int` param and the top-level static-
field-emission loop check against. Scoped to genuinely TOP-LEVEL fns/
statics only, matching this whole paragraph's own framing — impl/
`extend` methods (dispatched by receiver type, not referenced by a
qualified holder-class name the way a top-level fn is) aren't gated by
`pub` at all, unchanged.

```
module defs;

pub struct Items { }   // this file's first struct -- the "named holder"

pub static COUNT: Int = 7;
pub fn bump(x: Int) -> Int { return x + 1; }
```

```java
// From plain Java: Items.COUNT and Items.bump(x) are real static
// members on defs.Items, not an undiscoverable defs.$Fns.
int n = defs.Items.bump(defs.Items.COUNT);
```

This is exactly the shape a Forge-style "registry holder" class needs
(`DeferredRegister`/`RegistryObject` fields other files read by name) —
see `kubejs-aisle-tool/src/main/hc/Items.hc` for a real one, compiling to
`net.oktawia.structruretokubejsaisles.defs.Items` with a real
`Items.COPY_TOOL` field other Java files in that mod read directly.
Scoped to exactly "first struct wins" per file — a file mixing multiple
structs with top-level fns still only merges onto the first one
declared.

**Grouped by file, not just by module** — deliberately, and this
matters the moment `sourceDir`/directory-mode compilation is in the
picture: multiple files can (and in a real project do) legitimately
share one `module` line, each meaning its own struct to keep its own
separately-named class. `kubejs-aisle-tool`'s `client` module has three
files this way (`CopyToolHudOverlay.hc`, `CopyToolSelectionRender.hc`,
`RenderUtil.hc`), each with its own struct — grouping by module alone
would have silently merged all three files' top-level fns onto
whichever struct happened to be first, breaking the other two files'
`OtherStruct::its_fn(...)`-shaped call sites the moment directory mode
put them in one compile together. A file with no struct at all (a
fn-only file like `Aisletool.hc`/`Copytool.hc`, both sharing a module
too) still gets its own file-named holder in that case, not a shared
`$Fns` — matches the exact TitleCase-file-name-is-the-class-name
convention a single-file compile's own entry class already follows.
Verified directly: `Aisletool.hc` and `Copytool.hc` compiled together
(no `fn main()` anywhere in either) still produce two separately-named
`Aisletool`/`Copytool` classes, not one merged class under an arbitrary
name — real regression coverage exists for exactly this shape now
(`Foo.hc`/`Bar.hc` sharing a module, each keeping its own class; a
fn-only file with no struct at all, arbitrarily chosen as the
compile's fallback "entry," still keeping its own name instead of
being renamed to the directory's own basename).

See `examples/modules/` for a working two-module example (`extend`
reaching across module boundaries, a private struct genuinely
inaccessible cross-module — verified with `javap`, not just by reading
the source). **Re-verified with a fresh `javap` run, 2026-09-23**,
after the regression-and-fix described in the `pub` bullet above:
`javap minecraft/machine/InternalConfig.class` now genuinely prints
`class minecraft.machine.InternalConfig` (no `public`), vs. `Furnace`'s
`public class minecraft.machine.Furnace` — real, current confirmation,
not a stale claim inherited from the original Kotlin compiler.

### `priv` — a minimal cross-file visibility check (directory-mode compiles only)

**Shipped, 2026-09-23.** Not a fix for `pub`'s own lapsed enforcement above, and not real per-scope
resolution either — see `IDEAS.md`'s own "A minimal import/visibility system" entry for the full
design and the real, disclosed scope cuts. The short version: `priv struct Foo { ... }` / `priv
enum` / `priv interface` marks a top-level TYPE invisible to every OTHER file in the same
directory-mode compile (not a JVM access-control mechanism at all — a compile-time, name-based
check, run by `Driver.hotc`'s own `check_private_visibility` before `merge_program` ever collapses
per-file identity away). Scoped to types only, not `fn`/`static`, and to type-NAME-shaped
references only (struct-literal/static-call/cast/`is`/arena-constructor targets, every field/
param/return type string, `extends`/`impl ... for`/`extend` targets) — never bare value/call
references, since those would need real local-scope tracking to avoid false positives on an
unrelated local variable sharing a name with another file's private type.

```
// lib.hotc
priv struct Cache { hits: Int }
struct Point { x: Int, y: Int }
fn make_cache() -> Cache { return Cache { hits: 0 }; }
fn describe_point(p: Point) -> String { return "({p.x}, {p.y})"; }
```

```
// main.hotc, same directory
fn main() {
    let p = Point { x: 3, y: 4 };
    print(describe_point(p));   // fine -- uses the public surface only
    let c = make_cache();       // fine -- never NAMES Cache directly
    // let c2: Cache = make_cache();  -- would be a real compile error:
    // 'Cache' is declared 'priv' in lib.hotc and can't be referenced from main.hotc
}
```

A real, previously-latent `Vec<T>` monomorphization gap was found building the enforcement pass
itself: the natural-looking implementation (parse every file once into a `Vec<Program>`, check
it, then merge) compiles fine as source but crashes with `NoClassDefFoundError: Vec$Program` the
moment the compiled class is actually loaded — `Program` is by far the largest struct in this AST,
and this is the same broader class of gap already documented above for `Vec<Vec<T>>` struct
fields, just one level shallower and on a much bigger struct. Fixed by never constructing that
`Vec<Program>` at all: `check_private_visibility` takes the raw file-path list and re-parses every
file itself (twice — once to collect every `priv` name, once to walk every file's own references),
at the cost of parsing each file in a directory twice instead of once. Not perf-critical, same
tolerance `check_module_dirs` (right below) already established. Verified against
`examples/priv_visibility_ok/` (a valid consumer) and `examples/priv_visibility_violation/` (a
consumer directly naming the private type, correctly rejected with the exact error above). Full
example regression suite: zero new failures. Self-hosting verified to a true fixed point.

### Global state: `pub static NAME: Type = initExpr;`

**Currently broken in `selfhost/` for non-trivial init expressions**:
`examples/statics.hc` (a `static counter: Int = 0` plus a
`static log: Vec<String> = vec_of("boot")`) crashes the compiler itself
during codegen (`StringIndexOutOfBoundsException` inside ASM's
`Frame.pop`, called from `CodeGen.gen_expr`/`gen_static_init`) rather
than compiling — a bytecode-generation bug in `selfhost/`, not a
language-design issue. A bare `Int` static may well work; the `Vec<T>`
initializer is what's implicated here.

The prerequisite `open registry`/`register` (mentioned above) actually
needed: every value in Hot Chocolate used to be either a local (owned by
some enclosing scope) or a struct field (owned by some instance) — there
was no way for two unrelated call sites, let alone two separate modules,
to share and mutate *the same* persistent object. `static` is that:

```
module minecraft.machine;

pub static machineTiers: Registry<String> = registry_new("ulv", "Ultra Low Voltage");

pub fn registerTier(key: String, label: String) {
    machineTiers.register(key, label);
}
```

```
module phoenix.machines;

// A different module, contributing a new registry entry into machines.hc's static --
// this is the actual mechanism behind `open registry`/`register`.
pub fn seedPhoenixTiers() {
    registerTier("hv", "High Voltage (Phoenix)");
}
```

- Compiles to a real static field on its declaring module's holder
  class, initialized once in that class's `<clinit>` — `pub`/private
  maps onto `ACC_PUBLIC`/package-private exactly like everything else
  here.
- The initializer is checked with no locals, but every *already-declared*
  static is in scope — so a later static can read an earlier one:
  `pub static ITEMS: DeferredRegister = DeferredRegister::create(...);`
  followed by `pub static COPY_TOOL: RegistryObject = ITEMS.register(...);`
  works (see `Items.hc` again). This is backward-reference-only by
  construction, not a general dependency solver — a static's init is
  checked before any *later* static is added to the table at all, so a
  forward reference is simply unresolvable (an "unknown variable" error),
  never a special circularity case to detect. Sound at codegen time too:
  same-module statics initialize in this same declared order within one
  shared `<clinit>`, and a read of a different module's static just
  triggers that module's own class-init first, ordinary JVM
  `<clinit>`-on-first-use semantics.
- Reading/writing works exactly like a `var` local everywhere in the
  checker (same Ident/Assign/method-call code paths, injected into
  every fn's scope) with one difference: a read is *never* treated as a
  move. There's no scope for a global to be "used up" by — the next
  reader anywhere else in the program still needs to see it.

See `examples/registries/` for the full loop: `minecraft.machine`
declares and seeds a registry with no idea `phoenix.machines` exists;
`phoenix.machines` pushes a new entry into it through an ordinary `pub
fn` call; a third file reads both entries back out through the same
shared object.

**Known limitation, an honest scope cut, not an oversight**: this is
still genuine sharing only *within one compile invocation* — `static`
gives two modules in the same compile a real shared, mutable object,
but doesn't yet let two independently-compiled `.hc` builds (separate
jars, discovered only at JVM runtime, the real "mod B extends mod A
without A and B ever being compiled together" ecosystem story) share
one. That needs a runtime-discovery mechanism on top of this (a
ServiceLoader-style registration, or an explicit host-called
registration entrypoint) — `static` is the foundation it would sit on,
not that mechanism itself.

## Gradle plugin (`hc-gradle-plugin/`)

A real, reusable Gradle plugin — not a hand-copied task block. Any
project (like the real Minecraft mod this compiler is being built for)
that used to hand-write a `tasks.register('hcCompile', JavaExec) { ... }`
block per `.hc` file it wanted compiled can instead do:

```groovy
// settings.gradle
pluginManagement {
    includeBuild '../../HotChocolate'   // wherever your HotChocolate checkout is
}
```

```groovy
// build.gradle
plugins {
    id 'hc'
}

hotChocolate {
    version = "v0.1.5"   // resolves the compiler from JitPack -- see "Published on JitPack" below
    source file('hc/foo.hc')
    source file('hc/bar.hc')   // compiled after foo.hc, so it can `extern class` reach foo's output
}
```

#### `version` vs `compilerHome`: where the compiler itself comes from

`hotChocolate { }` needs exactly one of two ways to find the actual
compiler:

- **`version = "v0.1.5"`** — resolves the compiler as a real, already-
  published JitPack dependency (`com.github.P-H-O-E-N-I-X-PackForge:
  HotChocolate:v0.1.5`, adding the `jitpack.io` Maven repository to the
  consuming project automatically) instead of requiring a local
  `./gradlew installDist` against a sibling checkout. **This is the
  right default for essentially every consuming project**: no local
  HotChocolate checkout, no manual build step, a pinned version like any
  other dependency — reproducible on a machine (or CI runner) that's
  never touched the HotChocolate repo at all, the way `compilerHome`
  (pointing at whatever happens to be installed on one developer's
  machine) never was. Verified end-to-end: pointed a real consuming
  project's `hotChocolate { }` block at `version = "v0.1.5"`, confirmed
  the compiler genuinely resolves and runs over the network (not a
  silently-stale local copy) by observing it reject syntax added to the
  language *after* that tag was cut, then reverted back to local
  development's own `compilerHome`.
- **`compilerHome = file(...)`** — the local-dev escape hatch, unchanged
  from before: point it at a local `./gradlew installDist` output (e.g.
  a sibling checkout via `includeBuild`, as in the example above). Use
  this when you're actually working on the compiler itself and need an
  uncommitted, unpublished change to show up immediately. **Wins over
  `version` when both are set** — an explicit local override always
  beats a resolved artifact.

#### Zero-clone setup: resolving the plugin itself from JitPack, no `includeBuild`

`includeBuild` (above) needs a literal local checkout to point at — fine
for working on HotChocolate itself, overkill for just *using* it. The
plugin's own jar is published on JitPack too (see "Published on
JitPack" below), so a consuming project can skip `includeBuild` and
local cloning entirely:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "hc") {
                useModule("com.github.P-H-O-E-N-I-X-PackForge.HotChocolate:hc-gradle-plugin:${requested.version}")
            }
        }
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("hc") version "v0.1.6"
}

hotChocolate {
    version = "v0.1.6"
    sourceDir("src/main/hc")
}
```

The `resolutionStrategy.eachPlugin` block is required, not optional —
Gradle's normal `plugins { id(...) version ... }` resolution looks for a
*plugin marker* artifact at coordinates `hc:hc.gradle.plugin:vX.Y.Z`
(the plugin id used verbatim as the marker's groupId, a Gradle
convention independent of the project's own group), and JitPack has no
way to publish anything under that arbitrary namespace — it only ever
publishes under `com.github.<user>...`. `eachPlugin` sidesteps the
marker lookup entirely, mapping the plugin id directly to the real
module coordinate. That coordinate's own shape is also JitPack-specific
and easy to get wrong by guessing: a Gradle multi-module build's
submodule publishes as `com.github.<user>.<repo>:<module-name>:version`
(the repo name folded into the *group*, not a separate path segment) —
confirmed by fetching the real POM directly rather than assuming.

Verified end-to-end, from a project directory containing nothing but
the two files above (no HotChocolate checkout anywhere on disk, no
`includeBuild`): `id("hc") version "v0.1.6"` resolves the plugin,
`hotChocolate { version = "v0.1.6" }` resolves the compiler, and a real
`.hc` file compiles to a real `.class` file. The compiler's own
transitive dependencies (Kotlin stdlib, ASM) are ordinary Maven Central
artifacts JitPack doesn't mirror — the plugin adds `mavenCentral()`
itself when resolving via `version`, so this works even for a project
that hasn't already declared it (virtually every real project has, but
a genuinely bare one shouldn't need to know that).

Applying the plugin registers one `hcCompile<Name>` task per declared
source (`hcCompileFoo`, `hcCompileBar`, ...), each depending on the one
before it, and automatically wires the last one into `compileJava`'s
classpath and `processResources` — exactly the dependency chain that
used to be copy-pasted by hand into every consuming `build.gradle`,
now declared once and reused. Verified end-to-end against the real mod
project: `hcCompileAisletool`/`hcCompileCopytool` both run, their output
lands in the mod's jar (`javap`/`unzip -l` both confirm the real
`.class` files are present under the expected package), and
`./gradlew build` succeeds.

**`sourceDir`, the directory-mode counterpart** (alongside `source`, in
the same `hotChocolate { }` block):

```groovy
hotChocolate {
    compilerHome = file("$rootDir/../../HotChocolate/build/install/hotchocolate")
    sourceDir file('src/main/hc')
}
```

One `hcCompile<DirName>` task compiling every `.hc` file anywhere under
the directory (recursively — nested subdirectories, e.g. mirroring each
file's own `module` path, work exactly the same as a flat layout) as a
single shared, `extern`-free-between-them compile (the Gradle-plugin
side of the CLI's own `hc build <dir> <outDir>` — see "Multi-file
projects" above). Declaration order between files stops mattering
entirely once this is used — a real mod project's own `.hc` files can
reference each other directly, no `extern class Foo =
"already.compiled.Foo" { ... }` bridging needed for the project's *own*
compiled classes. Verified end-to-end against the real Minecraft mod:
switching its 9-file `hotChocolate { }` block (previously nine chained
`source(file(...))` entries, several needing hand-written `extern class`
bridges purely to reach each other's output) to one `sourceDir` removed
every one of those bridges, `javap` confirms the real mod's already-
public classes (`Aisletool`, `Copytool`, `Items`, ...) all kept their
exact same binary names and cross-package visibility, and `./gradlew
build` still succeeds.

**What this deliberately is not**: a replacement for Gradle, a
dependency resolver, or a Maven Central publisher — it's Gradle's own
composite-build (`includeBuild`) and plugin mechanisms, used the way
they're meant to be used. See `IDEAS.md` for why a from-scratch build
tool ("Marshmallow") was considered and declined; this plugin is the
actual right-sized version of that want.

**Resolved**: neither the compiler (`hotChocolate { version = "v0.1.6" }`)
nor the plugin itself (`plugins { id("hc") version "v0.1.6" }` + the
`eachPlugin` mapping) needs a local install or checkout anymore — see
"Zero-clone setup" above. `includeBuild` remains the right tool
specifically for working on HotChocolate's own compiler/plugin code,
not a requirement for using either one.

### Published on JitPack

The raw compiler jar (and the plugin's own jar, separately) *are* now
real, versioned, remotely-resolvable Maven artifacts — no local checkout
needed for these specifically:

```kotlin
dependencies {
    implementation("com.github.PhoenixVine-Digital.HotChocolate:hotchocolate:v0.1.14")
}
```

**Fixed 2026-09-11 — four real, independent bugs, all found the same
way: scaffolding Marshmallow, the first real end-to-end exercise of
this JitPack path (both the raw coordinate above AND the `hc` Gradle
plugin) since the self-hosted migration.** Nothing in this repo's own
build/test process had ever actually consumed its own published
artifact, so all four had been silently broken for a while:

1. **Wrong org.** The GitHub org renamed from `P-H-O-E-N-I-X-PackForge`
   to `PhoenixVine-Digital` — GitHub redirects `git clone`/`push`
   against the old name, but JitPack does not follow that redirect
   when resolving a Maven coordinate.
2. **Missing repo-name segment in the group.** JitPack's multi-module
   convention folds the repo name into the GROUP
   (`com.github.<user>.<repo>:<module>:<version>`) — this repo has
   been multi-module since `hc-gradle-plugin`/`hc-intellij-plugin`
   were split out, so the ROOT module needs it too, not just the
   submodules. `com.github.PhoenixVine-Digital` alone resolves to a
   URL with no route registered at all (a real Gradle "Read timed
   out," not a 404).
3. **Wrong artifact casing.** The artifact id is `hotchocolate`
   (lowercase, matching this repo's own `rootProject.name` in
   `settings.gradle.kts` exactly) — JitPack coordinate lookup is
   case-sensitive, so the capitalized `HotChocolate` 404s.
4. **The published jar never contained the compiler, or the stdlib.**
   The plain `jar` task only ever packages `sourceSets.main.output`
   (just `CodegenShim.class` since the self-hosted migration) — the
   real compiler classes (`SelfhostCLI`, all of `hc/selfhost/**`) come
   from the checked-in `selfhost/bootstrap` seed, and every stdlib
   topic's own `.hotc` SOURCE file (`stdlib/*.hotc`) comes from the
   plain `stdlib/` directory — both wired onto the local
   `runtimeClasspath`/resolved relative to the repo's own checkout for
   `./gradlew run`, but neither ever folded into the `jar` task's own
   output. `Driver.hotc`'s own `stdlib_topic_path` resolves every
   stdlib topic relative to the compiler PROCESS'S OWN WORKING
   DIRECTORY with no other fallback — and fires even for a program
   with zero `use` lines, since the "no `use` at all" default still
   injects the original five topics — so no external consuming
   project could have compiled ANYTHING through this artifact, not
   even a program using no stdlib features of its own. Confirmed via
   `jar tf` before/after (143 → 6 entries missing `SelfhostCLI.class`
   entirely; 0 → 9 real `stdlib/*.hotc` entries once both were fixed).
   Fixed by folding both `selfhost/bootstrap` and `stdlib/` into the
   `jar` task directly, and having the Gradle plugin extract the
   bundled `stdlib/*.hotc` entries next to each real compile's own
   `workingDir` before invoking the compiler (`stdlib_topic_path`'s own
   filesystem-relative lookup isn't something a consuming project's
   `hcCompile*` task can just leave to chance).

Verified end to end against the fully-fixed `v0.1.14`: a fresh
Marshmallow project (a real, separate consumer, zero local
HotChocolate checkout) resolved the plugin, the compiler, and the
`stdlib` bundle entirely from JitPack, compiled a real `.hc` source
file, and ran it — confirmed reproducible from a clean `.gradle`/
`build` state, not just a cached first success.

built on demand by JitPack from any pushed tag (`git tag vX.Y.Z && git
push origin vX.Y.Z`, then JitPack builds it the first time someone
requests it). Verified via a real `build.log` run, not just configured —
including catching and fixing a real bug before calling it done: the
root `build.gradle.kts` used to hardcode `group = "hc"` / `version =
"0.1.0"`, silently ignoring the `-Pgroup`/`-Pversion` JitPack passes in,
so the first tag actually published under the wrong coordinates despite
JitPack's own page advertising the right ones. Fixed by reading those
properties with local-dev fallbacks (`(findProperty("group") as
String?) ?: "hc"`), confirmed against a second real build.

**Known, accepted limitation**: Gradle's own "Multiple publications ...
will overwrite each other" warning shows up during the `hc-gradle-plugin`
module's publish step (`java-gradle-plugin`'s auto-created `pluginMaven`
publication collides in coordinates with one JitPack separately injects
for the same "java" component). It's real but non-fatal — the build
still succeeds and both modules still get correctly served under it,
confirmed via `build.log`. Two different fixes were tried and reverted
after real JitPack builds (not just local ones) showed each traded the
warning for something worse: applying `maven-publish` explicitly
silences it but silently drops this module from what gets served at all
(the early-detection "failure" that produces the warning turns out to
also be what triggers the JitPack codepath that packages every module
correctly); giving `pluginMaven` a distinct artifactId to dodge the
actual collision while keeping that codepath active fails outright
("Publication with name 'pluginMaven' not found") since whatever applies
`maven-publish` in that fallback isn't `java-gradle-plugin`'s own
internal application. Left alone, documented, not worth a third blind
attempt at outguessing JitPack's own undocumented internal packaging
heuristics for a cosmetic, non-fatal warning.

## Bounded compile-time evaluation (`const fn`)

**Shipped, 2026-09-24.** See `IDEAS.md`'s own "Bounded compile-time evaluation (`const fn`)" entry
for the full design, disclosed scope, and the real `Vec<T>`/`Option<T>` monomorphization gap found
building it. The short version:

```
const fn double_it(x: Int) -> Int { return x * 2; }
const LEVEL_COUNT: Int = double_it(2) + 1;   // 5, computed by the COMPILER itself
```

A `const fn` is a genuinely SEPARATE execution mode inside `Driver.hotc` (`eval_const_expr`/
`eval_const_stmts`, a small tree-walking interpreter over a deliberately narrow subset: literals,
matching-type arithmetic, `let`/`if`/`return`, and calls to other `const fn`s only) — it never
reaches `Checker.hotc`/`Codegen.hotc` as a real, compiled method at all. A `const NAME: Type =
expr;` is evaluated once and substituted as a literal everywhere `NAME` is referenced in ordinary
code (`fold_consts`, mirroring `Checker.hotc`'s own generic-substitution walkers) — no `static`
field, no `<clinit>` entry, genuinely zero runtime cost. See `examples/const_fn.hotc`.

## State machine syntax (`state Name { ... }`)

**Shipped, 2026-09-24.** See `IDEAS.md`'s own "State machine syntax" entry for the full design and
disclosed scope. The short version:

```
state PlayerState {
    Idle { Move -> Walking; }
    Walking { Stop -> Idle; Attack -> Attacking; }
    Attacking { Complete -> Idle; }
}
```

Entirely a `Parser.hotc`-level desugaring (zero `Checker.hotc`/`Codegen.hotc` changes) into a
fieldless states `enum`, a fieldless events `enum` (`PlayerStateEvent`), and a generated
`PlayerState_transition(current, event) -> PlayerState` fn built from an outer `match current` /
inner `match event`. An event with no matching arm in the current state is a silent no-op (a
generated `_ => { return <CurrentState> {}; }` wildcard) — deliberately a *fresh literal of the
outer arm's own already-known state*, not `return current;`, because `check_match` doesn't
snapshot/restore move-checker state per arm the way `if`/`else` does; a real `return current;` in
one state's wildcard would spuriously fail "use of moved value 'current'" while checking the
*next* state's arm, since a move in one arm leaks forward into every arm checked after it. Real,
disclosed deviation from `IDEAS.md`'s own original sketch: transition lines drop the leading `on`
keyword (`Move -> Walking;`, not `on Move -> Walking;`) — `on` was already renamed to `handle`
earlier specifically because it collided with `Checker.hotc`'s own field-access-type-resolution
code, which uses bare `on`/`on0` as real local variable names; reusing it here would recreate that
same self-hosting hazard, and `IDENT ARROW IDENT SEMI` is already unambiguous inside a dedicated
`StateName { ... }` block without any leading keyword. See `examples/state_machine.hotc`.

## IntelliJ plugin (`hc-intellij-plugin/`)

A real, hand-written IntelliJ Platform plugin (own lexer/PSI parser/annotator/type-checker/
references/completion, not a thin wrapper) that mirrors the actual compiler's own grammar closely
enough to navigate real `.hc`/`.hotc` code by, without being a byte-for-byte port — see
`HCPsiParser.kt`'s and `HCLexer.kt`'s own file headers for exactly what's simplified. Because it's
a second, independently-maintained implementation of the same grammar, it structurally lags the
real compiler: a new keyword/AST shape landing in `selfhost/lexer/Lexer.hotc`/`selfhost/parser/
Parser.hotc` doesn't automatically teach this plugin about it, and the gap shows up as real, false
"unexpected token" parse-error squiggles on code the actual compiler accepts cleanly.

**Caught up, 2026-09-23**: seven real reserved keywords the real compiler had gained across
several recent features but this plugin's lexer never learned — `unit` (units-as-types),
`parallel`/`sequence` (parallel-for / coroutines), `typestate`/`state` (typestates), `event`/
`handle` (events/signals) — plus real `Char` literal lexing (`'a'`, `'\n'`, `'\''`, mirroring
`Lexer.hotc`'s own `char_literal`; `Long`'s `L`-suffix literal was already handled here, just
never wired into `LITERAL_EXPR`'s CHAR-less token set). New PSI declaration/statement shapes added
to match: `UNIT_DECL`, `TYPESTATE_DECL` (with nested `STATE_DECL`), `EVENT_DECL`, `HANDLE_DECL`,
`PARALLEL_STMT` (`parallel for x in expr { }`), `SEQUENCE_STMT` (`sequence { }`). Array slicing
(`arr[start..end]`/`arr[start..=end]`, the same day it landed in the real compiler — see this
file's own "Added 2026-09-23" entry above) got a new `SLICE_EXPR`, parsed in `HCPsiParser`'s own
postfix-loop by peeking for `DOTDOT`/`DOTDOTEQ` right after the first bracketed expression, same
as the real compiler's `postfix_loop`. All of the above are parse-shape-only, same "trust it, let
a real compile reject anything actually invalid" leniency this whole plugin already uses
elsewhere — no attempt at replicating the real compiler's own semantic validation for any of them
(e.g. `typestate`'s "every `impl S` needs a matching `state S`" check, or `unit`'s base-type
restriction). The separate, hardcoded keyword list `HCCompletionContributor.STATEMENT_KEYWORDS`
uses for Ctrl+Space suggestions had independently fallen behind the same way (missing `use`/
`component`/`system`/`resource` from an EARLIER round of this same gap, on top of the seven above)
— caught up too, plus `Char` added to `PRIMITIVE_TYPE_NAMES`.

**Also fixed the same day: go-to-definition on stdlib/external-library names never resolved at
all.** `HCReferences.filesInScope` (the function every reference-resolution path in the plugin
funnels through) only ever searched the clicked file's own directory plus same-directory siblings
— a real, disclosed heuristic matching `hc run <dir>`'s own flat-namespace-per-directory
semantics, but one that has no way to reach `stdlib/*.hotc` at all, since it lives in its own
top-level directory, sibling to `examples/`, not inside it. Every stdlib struct/enum/`extern
class` (`Vec`, `Option`, `Registry`, every `extern class` binding a real JDK type) was therefore
unreachable by Ctrl+B/Ctrl+Click even though the SAME resolution machinery already handles an
`extern class` alias correctly once it can find the declaration at all (`isTypeDecl` already
covers `EXTERN_CLASS_DECL`). Fixed by widening `filesInScope` to also search the nearest ancestor
directory literally named `stdlib`, found by walking up from the clicked file's own directory
(capped at 8 levels, first match wins) — a project-layout heuristic in the same spirit as
`HCRunConfiguration.kt`'s own "shell out to this project's own gradlew" assumption, not a real
project-config lookup (this plugin has no notion of "where is this project's stdlib" beyond
"look for a directory with that literal name"). Jumping only ever lands on the stdlib
DECLARATION itself (an `extern class Foo = "java.lang.Foo" { ... }` binding, not the real JDK
class `Foo` any further) — a real, disclosed scope cut, not attempted this pass.

Full plugin test suite (`HCLexerTest`/`HCParserTest`/`HCAnnotatorTest`/`HCReferencesTest`/...,
328 tests before this pass) confirmed zero new failures from any of the above, plus new targeted
tests for every new shape (`HCParserTest`'s new unit/typestate/event/handle/parallel/sequence/
slice/char-literal tests, `HCReferencesTest`'s new stdlib-resolution test). Four pre-existing
failures (real bitwise-shift-operator, comprehension, positional-match-pattern, and tuple syntax
this plugin's parser has never supported at all — confirmed pre-existing by reproducing them
against the untouched baseline before starting this pass) are unrelated, disclosed, and NOT
addressed here — each is its own, considerably larger grammar gap (a whole new expression/pattern
shape apiece, not a missing keyword), left as an explicit follow-up rather than folded into this
one.

**The four items above, closed out, 2026-09-23**:

- **Bitwise `^` and real `<<`/`>>`/`>>>` shift.** Added `^` and `<<` to `HCTokenTypes.OPERATORS`
  (`>>`/`>>>` deliberately still don't get their own lexer token, mirroring the real compiler's own
  `Lexer.hotc` `LTLT` header exactly: a lone `>` still has to close a nested generic,
  `Vec<Vec<Int>>`). New `bitwiseXor` precedence level (between `|` and `&`) and a new `shift`
  level (between `comparison` and `term`) in `HCPsiParser.kt`, assembling `>>`/`>>>` from
  consecutive bare `>` tokens via the existing `lookAheadIsOp` helper, same technique the real
  compiler's own `is_double_gt`/`is_triple_gt` use. **Real bug found and fixed along the way**:
  the first cut advanced `repeat(op.length)` lexer tokens per shift operator (2 for `"<<"`, 2/3 for
  `">>"`/`">>>"`) — correct by coincidence for `>>`/`>>>` (two/three separate single-char tokens),
  but wrong for `<<` (ONE real 2-character token, not two), silently eating the operand after it
  too and corrupting every following argument in the same call. Only surfaced with a SECOND `<<`
  use in the same file (`bitwise_shift_xor.hotc`'s own `print(1 << 4); print(3 << 2);` — a single
  isolated `1 << 4` test case never exercises this misalignment). Fixed by tracking token COUNT
  separately from operator text.
- **Positional match patterns** (`Goblin(hp)`). New `LPAREN` branch in `HCPsiParser.variantPattern`,
  parallel to the existing `LBRACE` one, mirroring the real compiler's own `Parser.hotc`
  `match_arm` header (bare bind names only, no field renaming, bound by declaration-order position
  — resolved against the variant's real field order by a real compile, not this parser). Found and
  fixed two related false positives along the way: `HCAnnotator.collectLocalNames` and
  `HCReferences.findLocalBinding` both only ever looked for an `LBRACE` inside a `VARIANT_PATTERN`
  to find bind names, so a positional pattern's own binds were invisible to both go-to-definition
  and the unresolved-reference checker — `Goblin(hp) => { return hp; }` was flagging `hp` itself as
  a real, false "unresolved reference" the moment positional patterns started parsing at all.
- **List comprehensions** (`[result_expr for var_name in iter_expr if cond]`). `HCPsiParser
  .arrayLiteral` rewritten to decide its element type (`ARRAY_LIT_EXPR`/`COMPREHENSION_EXPR`) AFTER
  parsing the first inner expression rather than committing up front — mirrors the real compiler's
  own `array_lit`'s three-way `;`/`for`/`,` dispatch. Same false-positive class as positional
  patterns: `HCAnnotator.collectLocalNames`/`HCReferences.findLocalBinding` didn't know
  `COMPREHENSION_EXPR` had its own bound variable, so `[x * x for x in xs]` flagged its own `x` as
  unresolved. Fixed the same way (both functions reuse `declaredName`'s existing "first direct-
  child `IDENT`" extraction, safe here because `result_expr`/`iter_expr`/`cond` are always full
  expression subtrees, never a bare `IDENT` token directly under `COMPREHENSION_EXPR` itself).
- **Tuples** — turned out not to be a parser gap at all. `Tuple2<A, B>` (`stdlib/tuple.hotc`) is an
  ordinary two-type-param generic struct with a plain `tuple2(a, b)` constructor function; the
  real compiler needed zero new parser/checker/codegen work for it (see that file's own header),
  and neither did this plugin — `tuple_and_comprehension.hotc`'s own failure was purely the
  comprehension syntax on the SAME line (`[tuple2(x, x * x) for x in nums]`), fixed by the item
  above.

Verified via the same discipline as every other pass: full test suite (343 tests, up from 336)
before/after, zero new failures. The two failures that remain (`test real stdlib files parse with
no syntax errors`, `test real example programs produce no unexpected annotator errors`) are
unrelated to all four items above and were already failing before this pass started — a separate,
disclosed nested-generic-struct-literal gap (`Vec<Entry<V>> { ... }`, `looksLikeGenericLit`'s own
bounded lookahead only ever handles ONE level of `<...>` nesting, matching the real compiler's own
identically-scoped `looks_like_generic_lit`) plus a broader, pre-existing annotator allowlist gap
(the directive-name check doesn't yet know about `@derive`/`@requires`/`@ensures`/`@tunable`/
`@deterministic`/lifecycle annotations, and a few stdlib collection constructors like
`hash_map_new` aren't in scope for name resolution) — neither attempted in this pass.

