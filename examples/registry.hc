interface Block {
    fn name(&self) -> String;
}

struct StoneBlock { }
impl Block for StoneBlock {
    fn name(&self) -> String { return "stone"; }
}

struct DirtBlock { }
impl Block for DirtBlock {
    fn name(&self) -> String { return "dirt"; }
}

fn main() {
    let first: &dyn Block = &StoneBlock { };
    var reg = registry_new("stone", &first);
    let second: &dyn Block = &DirtBlock { };
    reg.register("dirt", &second);

    let found = reg.get("dirt");
    match found {
        Some { value } => { print(value.name()); }
        None => { print("not found"); }
    }

    let missing = reg.get("gold");
    match missing {
        Some { value } => { print(value.name()); }
        None => { print("not found"); }
    }

    print(reg.length());
}
