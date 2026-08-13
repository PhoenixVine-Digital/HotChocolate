// `System.getSecurityManager()` is a real, non-generic JDK static method that genuinely returns
// null in the normal case (no SecurityManager installed) -- a clean extern-typed nullable
// example, the same shape as Forge's `UseOnContext.getPlayer()` (nullable for a non-player use
// context) that originally motivated this feature.
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
    // `sm` is narrowed to non-nullable SecurityManager here -- calling a method on it must
    // type-check with no cast/workaround needed.
    return sm.toString();
}

fn main() {
    print(describe_security_manager());
}
