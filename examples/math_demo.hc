// Math stdlib demo -- java.lang.Math wrapper + Vec2/Vec3/Vec4/Mat4/Quaternion. See
// stdlib/math.hotc's own header for the full design.
use math;

fn main() {
    // --- java.lang.Math, seamlessly ---
    print(Math::sqrt(2.0));
    print(Math::PI);
    print(abs_int(-5));

    // --- Vec3 ---
    let a = Vec3 { x: 1.0f, y: 2.0f, z: 3.0f };
    let b = Vec3 { x: 4.0f, y: 5.0f, z: 6.0f };
    let sum = a.add(&b);
    print("{sum.x} {sum.y} {sum.z}");
    print(a.dot(&b));
    let x_axis = Vec3 { x: 1.0f, y: 0.0f, z: 0.0f };
    let y_axis = Vec3 { x: 0.0f, y: 1.0f, z: 0.0f };
    let z_axis = x_axis.cross(&y_axis);
    print("{z_axis.x} {z_axis.y} {z_axis.z}"); // expect 0 0 1

    // --- Mat4: identity leaves a point unchanged ---
    let p = Vec3 { x: 7.0f, y: 8.0f, z: 9.0f };
    let ident = Mat4::identity();
    let p2 = ident.transform_vec3(&p);
    print("{p2.x} {p2.y} {p2.z}"); // expect 7 8 9

    // --- Mat4: translation ---
    let t = Mat4::translation(10.0f, 0.0f, 0.0f);
    let p3 = t.transform_vec3(&p);
    print("{p3.x} {p3.y} {p3.z}"); // expect 17 8 9

    // --- Mat4: rotation_y by 90 degrees on the X axis -> should land on -Z ---
    let half_pi = (Math::PI / 2.0) as Float;
    let ry = Mat4::rotation_y(half_pi);
    let rotated = ry.transform_vec3(&x_axis);
    print("{rotated.x} {rotated.y} {rotated.z}"); // expect ~0 0 -1

    // --- Quaternion: from_axis_angle around Y by 90 degrees, applied via to_mat4 ---
    let q = Quaternion::from_axis_angle(&y_axis, half_pi);
    let qm = q.to_mat4();
    let rotated2 = qm.transform_vec3(&x_axis);
    print("{rotated2.x} {rotated2.y} {rotated2.z}"); // expect ~0 0 -1, matches rotation_y

    // --- Quaternion slerp: halfway between identity and a 90-degree rotation ---
    let half_rot = Quaternion::identity().slerp(&q, 0.5f);
    let hm = half_rot.to_mat4();
    let rotated3 = hm.transform_vec3(&x_axis);
    print("{rotated3.x} {rotated3.y} {rotated3.z}"); // expect ~0.707 0 -0.707 (45 degrees)
}
