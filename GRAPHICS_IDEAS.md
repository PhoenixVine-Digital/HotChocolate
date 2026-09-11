# Graphics/rendering — design doc

**Status, 2026-09-11**: windowing AND first-geometry milestones both landed.
`stdlib/window.hotc` (`use window;`) gives real GLFW window creation/
lifecycle, keyboard input, and minimal OpenGL (`GL11`) clearing/presenting,
via LWJGL. `stdlib/graphics.hotc` (`use graphics;`, depends on `window`)
adds real geometry: `Shader::compile(vertex_src, fragment_src)` compiles
and links a real GLSL program; `Mesh::from_floats(vertices, vertex_count)`
uploads a real VBO/VAO. Both verified with REAL rendering on real hardware
(not just a compile check) through Marshmallow: a real colored triangle,
compiled shaders, real vertex buffers, drawn every frame. See
ARCHITECTURE.md's own "Standard library modules" section for the full
technical writeup of what shipped (the `JCharSequenceWin` alias + `as` cast
`glfwCreateWindow`'s/`glShaderSource`'s title/source params need to pass
real `--classpath` signature verification, the `Window::new`-not-`open`
naming trap, the `&self`-or-implicitly-static extern-method trap that hit
`JFloatBufferGfx::put`/`::flip`, etc.).

**The one decision that matters most for everything else in this doc,
made explicitly, not defaulted into**: **Vulkan is the real target before
Marshmallow ships. OpenGL is the deliberate, disclosed INTERIM backend, not
the final one.**

## Why Vulkan, stated plainly (so this doesn't get re-litigated later)

Real motivating case, not a hypothetical: Minecraft's own long-running
rendering pain — frame-time variance, driver-bound CPU overhead, the entire
reason a performance-mod ecosystem (Sodium and friends) exists at all —
traces straight back to OpenGL's driver-managed state machine and
single-threaded command submission model. Vulkan's whole design point is
explicit control over GPU memory, synchronization, and multi-threaded
command-buffer submission — the exact category of problem that bit the
project this engine is explicitly trying to learn from. This was decided in
conversation, directly, not inferred: "before shipping I 100% want vulkan,
minecraft has a lot of issues I can learn from and opengl was one of them."

## Why OpenGL first anyway, and what that commits us to

Vulkan's own setup cost is real and large — a working triangle needs
instance/device/queue/swapchain/pipeline/descriptor-set setup, hundreds of
lines before anything renders, versus OpenGL's handful of calls. Frontloading
that cost before *anything* renders would have stalled the actual "can we
see anything at all" milestone this whole `window` topic exists to prove.
OpenGL is the pragmatic way to get real content on screen NOW, with Vulkan as
the known, explicit destination once enough real rendering exists that
OpenGL's own limits (not hypothetical ones) start actually showing up.

**The real commitment this makes, so it doesn't get forgotten under
schedule pressure**: whatever `graphics.hotc` (the next layer — shaders,
vertex buffers, draw calls, built on OpenGL) exposes to Marshmallow's own
game-side code should describe *what to draw*, not *how OpenGL draws it* —
draw calls, materials, meshes, render passes as concepts, not raw
`GL11`/`GL20`/`GL30` calls leaking into caller code. The test for whether
this held: swapping the backend to Vulkan later should mean rewriting
`graphics.hotc`'s own internals, not every call site in Marshmallow that
asked to draw something. `stdlib/window.hotc`'s own `Window` struct
already sets this precedent (`clear`/`present`, not raw `GLFW::glfwSwapBuffers`
sprinkled through caller code) — `graphics.hotc` needs to keep it up at a
much larger surface area.

## Explicitly deferred (not silently dropped — tracked here)

- ~~**Real geometry**~~ — landed, 2026-09-11: `graphics.hotc`'s own
  `Shader`/`Mesh` (shader compile/link, VBO/VAO upload, real draw calls),
  fixed vertex layout only (`x, y, z, r, g, b` interleaved). Still real,
  narrower follow-ups from here: textures/samplers, uniforms (no uniform
  bindings exist yet -- everything so far is vertex-attribute-driven, no
  per-draw-call constant data like a transform matrix), a general vertex-
  format description (multiple layouts, not just the one fixed shape),
  index buffers (EBO -- drawing with `glDrawElements`, not just
  `glDrawArrays`), and depth/blending state.
- **The Vulkan migration itself** — not started. Real engineering, likely
  bigger than the OpenGL layer it replaces (see "Why OpenGL first," above,
  for the real size gap). See "Open questions" below for what actually
  needs deciding before it's buildable, not just "someday."
- **Cross-platform LWJGL natives** — `window.hotc`/Marshmallow's own
  `build.gradle.kts` are pinned to `natives-windows` right now (this
  machine's own platform), a real, disclosed scope cut, not an oversight.
  Picking the right natives classifier per-platform at build/run time is
  real (if mechanical) Gradle work, worth doing the moment this needs to
  run somewhere else.
- **`@profile`-style GPU/frame timing** — nothing here yet; worth revisiting
  once real draw calls exist to time, same "trivial once the thing it
  measures exists" reasoning `ECS_IDEAS.md`'s own `@profile` entry used.

## A relevant existing idea, not graphics-specific but worth linking here

`IDEAS.md`'s own "phantom-type resource states" entry (`Texture<Loading>`/
`Texture<Ready>`, compile-time-checked via a phantom generic type param, no
engine runtime needed) is exactly the shape of compile-time safety this
project's whole "catch it at compile time, not runtime" philosophy already
applies elsewhere (move/borrow checking, `@sendable`, `--classpath` extern
verification). Worth real consideration once `graphics.hotc` has real
loadable resources (textures, meshes) whose "is this actually ready to use
yet" state is exactly the kind of bug class that idea targets — a real
candidate for BACKEND-AGNOSTIC infrastructure that would keep paying off
across the eventual OpenGL-to-Vulkan swap, unlike anything backend-specific.

## Open questions

Real picks nothing above resolves — written here so answering them later
doesn't mean re-deriving the question first:

1. **What actually triggers starting the Vulkan migration?** "Once OpenGL's
   own limits start showing up" is the stated principle, but that's a
   feeling, not a check. A concrete trigger (a specific perf target missed?
   a specific feature OpenGL genuinely can't do reasonably? a fixed point in
   Marshmallow's own feature list, e.g. "once multiple render passes/post-
   processing are needed"?) would make this decidable instead of
   perpetually deferred.
2. **How much of `graphics.hotc`'s own design should be informed by
   Vulkan's concepts UP FRONT**, even while implemented on OpenGL — e.g.
   modeling "render pass"/"pipeline state"/"command buffer" as real
   `graphics.hotc`-level concepts now (even if they compile down to
   ordinary immediate-mode GL calls today) so the eventual Vulkan backend
   is a real 1:1 swap underneath the SAME concepts, versus keeping
   `graphics.hotc` OpenGL-shaped now and accepting a real redesign (not
   just a backend swap) when Vulkan actually lands. The former front-loads
   design cost now; the latter defers it, at the cost of the migration
   being bigger when it comes.
3. **LWJGL's own Vulkan bindings** (`org.lwjgl.vulkan.*`) are real and
   available the same way GLFW/GL11 are (an ordinary Maven Central
   dependency, same `lwjgl-bom`/natives-classifier pattern `window.hotc`'s
   own `build.gradle.kts` wiring already established) — nothing new needed
   there when the time comes, just a much larger `extern class` surface to
   bind (instance/device/queue creation, swapchain, pipeline/descriptor-set
   objects, command buffer recording). Worth confirming this assumption
   against LWJGL's real current docs once the migration actually starts,
   not assumed stale by then.
