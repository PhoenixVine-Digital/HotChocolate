struct Resource {
    id: Int,
}

impl Resource {
    fn drop(&mut self) {
        print(self.id);
    }
}

fn make(id: Int) -> Resource {
    return Resource { id: id };
}

fn main() {
    print(1000);
    let a = Resource { id: 1 };
    let b = Resource { id: 2 };
    print(2000);
    // a and b drop here in reverse order: 2, then 1

    var count = 0;
    while count < 2 {
        let temp = Resource { id: 100 + count };
        count = count + 1;
        // temp drops at the end of each loop iteration
    }

    let early = Resource { id: 99 };
    if true {
        let inner = Resource { id: 50 };
        // inner drops here
    }
    print(3000);
    // early drops here

    let moved = Resource { id: 7 };
    drop(moved); // manual drop -- fires immediately, not again at scope end

    let returned = make(42);
    print(returned.id);
    // returned drops here
}
