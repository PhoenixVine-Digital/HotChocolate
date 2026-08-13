module phoenix.machines;

// Adds a brand-new method to `Machine` from a *different* module than the one that declared
// it -- no edit to machines.hc needed. Only possible because `Machine` was declared `open`.
extend Machine {
    fn phoenixTier(&self) -> Int {
        return 1;
    }
}

// Extensions work on concrete structs directly too, not just open traits.
extend Furnace {
    fn describe(&self) -> String {
        return self.name() + ", a phoenix machine";
    }
}
