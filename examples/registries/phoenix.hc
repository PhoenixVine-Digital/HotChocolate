module phoenix.machines;

extend Machine {
    fn phoenixTier(&self) -> String {
        return "hv";
    }
}

// Contributes a brand-new entry into machines.hc's registry, from a completely different
// module -- the actual point of `static` existing: two unrelated call sites (this one, and
// anything reading `machineTiers` back in main.hc) mutate/observe the exact same shared object.
pub fn seedPhoenixTiers() {
    registerTier("hv", "High Voltage (Phoenix)");
}
