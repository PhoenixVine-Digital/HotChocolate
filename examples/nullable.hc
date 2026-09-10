// `System.getSecurityManager()` is a real, non-generic JDK static method that genuinely returns
// null in the normal case (no SecurityManager installed) -- a clean extern-typed nullable
// example, the same shape as Forge's `UseOnContext.getPlayer()` (nullable for a non-player use
// context) that originally motivated this feature.
extern class SecurityManager = "java.lang.SecurityManager" {
    fn toString(&self) -> String;
}
extern class System = "java.lang.System" {
    static fn getSecurityManager() -> SecurityManager?;
    // `getProperty` genuinely returns a possibly-null native `String` -- unlike `SecurityManager?`
    // above (a `TyStruct`-shaped nullable value), this exercises `String?`'s own gates: `+`,
    // string interpolation, and `==`/`!=` against `null` (null-safe -- `Objects.equals`-based, not
    // a raw `.equals()` call that would NPE the moment the left operand is genuinely null) all need
    // their own "possibly-null String used without a null check" enforcement, on top of the
    // struct/extern-class case `SecurityManager?` already covers.
    static fn getProperty(key: String) -> String?;
}

fn describe_security_manager() -> String {
    let sm = System::getSecurityManager();
    if sm == null {
        return "no security manager installed";
    }
    // `sm` is narrowed to non-nullable SecurityManager here -- calling a method on it must
    // type-check with no cast/workaround needed.
    return sm.toString();
}

fn describe_property(key: String) -> String {
    let v = System::getProperty(key);
    if v == null {
        return "no such property";
    }
    // `v` is narrowed to non-nullable String here -- `+`, `.method()`, and interpolation all work.
    return "found: " + v.trim();
}

fn main() {
    print(describe_security_manager());
    print(describe_property("java.version"));
    print(describe_property("this.property.does.not.exist"));
}
