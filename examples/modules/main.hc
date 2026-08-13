fn main() {
    let f = Furnace { };
    print(f.name());
    print(f.describe());
    print(f.phoenixTier());

    let m: &dyn Machine = &f;
    print(m.name());
    print(m.phoenixTier());
}
