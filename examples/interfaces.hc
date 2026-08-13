interface Describable {
    fn name(&self) -> String;
    fn tag(&self) -> String {
        return "[thing] " + self.name();
    }
}

struct Player {
    pname: String,
    hp: Int,
}

impl Describable for Player {
    fn name(&self) -> String {
        return self.pname;
    }
}

struct Item {
    iname: String,
}

impl Describable for Item {
    fn name(&self) -> String {
        return self.iname;
    }

    fn tag(&self) -> String {
        return "<item> " + self.iname;
    }
}

fn announce(d: &dyn Describable) {
    print(d.tag());
}

fn main() {
    let p = Player { pname: "Rin", hp: 100 };
    let i = Item { iname: "Sword" };

    // Static dispatch on concretely-typed values, still through the interface method.
    print(p.tag());
    print(i.tag());

    // Dynamic dispatch through &dyn -- same call site, different concrete types.
    announce(&p);
    announce(&i);
}
