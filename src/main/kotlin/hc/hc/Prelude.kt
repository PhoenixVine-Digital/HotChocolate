package hc

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
