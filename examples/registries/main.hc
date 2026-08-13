fn main() {
    print(describeTier("ulv"));   // seeded directly in machines.hc
    print(describeTier("hv"));    // not registered yet

    seedPhoenixTiers();           // phoenix.machines module contributes an entry

    print(describeTier("hv"));    // now visible, through the same shared registry
    print(machineTiers.length());

    let f = Furnace { };
    print(f.name());
    print(f.phoenixTier());
}
