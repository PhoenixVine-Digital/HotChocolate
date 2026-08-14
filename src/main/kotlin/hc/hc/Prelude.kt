package hc

// A tiny self-hosted stdlib: written in Hot Chocolate itself, not special-cased in the
// compiler, and merged into every compiled program (see mergeProgram in Main.kt). Since
// generic declarations are never checked/codegen'd until actually instantiated, including
// this unconditionally costs nothing for a program that never references it.
//
// `Vec<T>` is a real growable array -- `[T]` alone is fixed-size. Growing it needed one
// checker relaxation: `[value; count]` used to require a Copy `value` (Int/Bool/String);
// it now allows any type, aliasing the same reference into every slot rather than moving it
// (memory-safe on the JVM regardless of element type -- storing one reference into N array
// slots is exactly what any array-fill already does under the hood). That's what lets `push`
// build a new, larger backing array by filling it with the just-pushed value as a throwaway
// filler for the slots it's about to overwrite anyway.
//
// `Registry<T>` is the extensible-open-set counterpart to `enum`'s closed one: built on
// `Vec`, string-keyed, `.get` returns `Option<T>` so a missing key is a normal, matchable
// value rather than a crash or a sentinel. This is the intended tool for something like a
// Minecraft-style block/item registry -- `enum` is for a fixed, known-at-compile-time set of
// cases (`Shape`, `Direction`); `Registry<dyn Trait>` is for a set that grows as more things
// get registered into it, each identified by a stable key.
//
// Known limitation: no true `Vec::new()` with zero elements -- construction always needs at
// least one seed value (`vec_of`/`registry_new`), since this language has no per-type
// default/zero value to fill an empty backing array with. A real gap, not just a caveat.
// `module hc.prelude;` is load-bearing, not decorative: with no `module` line, every prelude
// declaration -- and, critically, every monomorphized instantiation of a generic one
// (`Option_net_minecraft_core_BlockPos`, `Vec_Int`, ...) -- compiles into the JVM's default
// (unnamed) package. That's invisible for a plain `hc run`/`hc build` program, but a real Forge
// mod's classes load through a module-aware classloader (`cpw.mods.cl.ModuleClassLoader`, from
// `securejarhandler`), which cannot resolve a class with no package at all -- shipped a real
// `NoClassDefFoundError` at runtime the moment a mod actually instantiated `Option<T>` with a
// real Minecraft type, despite compiling and `javap`-verifying cleanly (module resolution is a
// classloading-time concern, invisible to the compiler and to a bytecode-shape check alike). A
// monomorphized instantiation's `moduleName` is copied straight from its template (see
// `getOrInstantiateStruct`/`getOrInstantiateEnum`/`getOrInstantiateFn` in Checker.kt), so this
// one line fixes every current and future generic prelude instantiation at once, not just
// `Option<T>`.
val PRELUDE_SOURCE = """
module hc.prelude;

pub enum Option<T> {
    Some { value: T },
    None,
}

// The HC-native counterpart to `try`/`catch`: `try`/`throw` are for real JVM exceptions
// (extern-declared Throwable types, going through ASM's actual exception-table mechanism --
// see the README's Error handling section), while `Result<T, E>` is for representing "this
// HC-native operation can fail" as an ordinary matchable value, the same way `Option<T>`
// represents "this can be absent" -- no exception, no special control flow, just an enum.
pub enum Result<T, E> {
    Ok { value: T },
    Err { error: E },
}

pub struct Vec<T> {
    data: [T],
    len: Int,
}

impl<T> Vec<T> {
    fn push(&mut self, value: T) {
        if self.len == self.data.length {
            var newCap = self.data.length * 2;
            if newCap == 0 {
                newCap = 4;
            }
            var newData = [value; newCap];
            var i = 0;
            while i < self.len {
                newData[i] = self.data[i];
                i = i + 1;
            }
            self.data = newData;
        }
        self.data[self.len] = value;
        self.len = self.len + 1;
    }

    fn get(&self, i: Int) -> T {
        return self.data[i];
    }

    fn set(&mut self, i: Int, value: T) {
        self.data[i] = value;
    }

    fn length(&self) -> Int {
        return self.len;
    }
}

pub fn vec_of<T>(first: T) -> Vec<T> {
    return Vec { data: [first; 4], len: 1 };
}

pub struct RegistryEntry<T> {
    key: String,
    value: T,
}

pub struct Registry<T> {
    entries: Vec<RegistryEntry<T>>,
}

pub fn registry_new<T>(firstKey: String, firstValue: T) -> Registry<T> {
    return Registry { entries: vec_of(RegistryEntry { key: firstKey, value: firstValue }) };
}

impl<T> Registry<T> {
    fn register(&mut self, key: String, value: T) {
        self.entries.push(RegistryEntry { key: key, value: value });
    }

    fn get(&self, key: String) -> Option<T> {
        var i = 0;
        while i < self.entries.length() {
            let entry = self.entries.get(i);
            if entry.key == key {
                return Some { value: entry.value };
            }
            i = i + 1;
        }
        return None;
    }

    fn length(&self) -> Int {
        return self.entries.length();
    }
}
""".trimIndent()
