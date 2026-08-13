module minecraft.machine;

pub open interface Machine {
    fn name(&self) -> String;
}

pub struct Furnace { }
impl Machine for Furnace {
    fn name(&self) -> String { return "furnace"; }
}

// Not `pub` -- private to this module. Other modules can't reference it, and even if they
// tried to guess a matching qualified name, the JVM's own package-private access would refuse
// to load it cross-module.
struct InternalConfig { }
