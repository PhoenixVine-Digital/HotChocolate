arena struct Particle {
    x: Int,
    y: Int,
    alive: Bool,
}

fn main() {
    var particles = arena Particle[5];

    var i = 0;
    while i < 5 {
        particles[i].x = i * 10;
        particles[i].y = i * 20;
        particles[i].alive = true;
        i = i + 1;
    }

    print(particles[2].x);
    print(particles[2].y);
    print(particles[2].alive);

    particles[2].alive = false;
    print(particles[2].alive);
}
