module minecraft.machine;

pub open interface Machine {
    fn name(&self) -> String;
}

pub struct Furnace { }
impl Machine for Furnace {
    fn name(&self) -> String { return "furnace"; }
}

// The registry itself: `Registry<T>` from the prelude, but held as a genuine global -- one
// persistent, shared instance for the whole program's lifetime, not a fresh one per caller.
// This is the actual mechanism behind `open registry`/`register`: any module that can see
// `machineTiers` (it's `pub`) can push new entries into it and every other module sees them,
// without machines.hc ever being recompiled or even knowing phoenix.hc exists.
pub static machineTiers: Registry<String> = registry_new("ulv", "Ultra Low Voltage");

pub fn registerTier(key: String, label: String) {
    machineTiers.register(key, label);
}

pub fn describeTier(key: String) -> String {
    let found = machineTiers.get(key);
    match found {
        Some { value } => { return value; }
        None => { return "unknown tier"; }
    }
}
