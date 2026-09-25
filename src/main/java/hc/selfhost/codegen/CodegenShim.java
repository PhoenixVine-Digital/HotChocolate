package hc.selfhost.codegen;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// The one step the self-hosted codegen genuinely cannot do itself: HC has no `Byte` primitive
// (see IDEAS.md), so `ClassWriter.toByteArray()` -- returning `byte[]` -- isn't callable from HC
// at all. This tiny same-project shim takes the finished ClassWriter and writes the bytes to
// disk, staying entirely on the Java side of that gap. Same pattern the earlier ASM-interop
// spike this session proved out.
public final class CodegenShim {
    private CodegenShim() {}

    public static void writeClass(ClassWriter cw, String path) {
        byte[] bytes = cw.toByteArray();
        Path target = Paths.get(path);
        try {
            // A module-qualified class name (e.g. "a/b/c/Name.class", once `Codegen.hotc`'s own
            // module-porting feature qualifies it) needs its real package directory structure to
            // already exist on disk -- unlike the previous default-package-only case, where `path`
            // was always just a bare filename in the current directory. `createDirectories` is a
            // no-op when the parent already exists (the default-package case), so this is safe for
            // every existing caller too.
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                out.write(bytes);
            }
        } catch (IOException e) {
            throw new RuntimeException("CodegenShim: failed writing " + path, e);
        }
    }

    // A second real gap: `ClassWriter.visitField`'s last param (the field's compile-time constant
    // value, only meaningful for static final fields) needs a real Java `null` for an ordinary
    // instance field -- HC's `null` literal only resolves against an already-known nullable
    // expected type (`let x: T? = null;`, `return null;`), not a bare call argument, so it can't
    // be passed directly from Codegen.hotc. Staying entirely on the Java side of that gap too.
    public static void visitInstanceField(ClassWriter cw, int access, String name, String descriptor) {
        cw.visitField(access, name, descriptor, null, null).visitEnd();
    }

    // Same real gap, a third instance of it: `ClassWriter.visit`'s and `.visitMethod`'s own
    // `signature` param (a *generic* signature, distinct from the plain descriptor) needs a real
    // `null` for "no generics" -- Codegen.hotc was passing `""` instead (the only value it could
    // construct without a `null` literal), which ASM writes as a genuinely malformed empty
    // Signature attribute per the JVM spec, since "" isn't a valid signature. That doesn't break
    // the real JVM's own bytecode verifier (which never reads Signature attributes -- they're
    // informational only, for reflection/generics), but it does break `javap` (crashes trying to
    // parse the empty signature) and is real, worth fixing on its own.
    public static void visitClassHeader(ClassWriter cw, int version, int access, String name, String superName, String[] interfaces) {
        cw.visit(version, access, name, null, superName, interfaces);
    }

    public static MethodVisitor visitMethodNoSig(ClassWriter cw, int access, String name, String descriptor, String[] exceptions) {
        return cw.visitMethod(access, name, descriptor, null, exceptions);
    }

    // Same real "needs a genuine Java null" gap once more: `ClassWriter.visitSource`'s second
    // param (a debug-info string, e.g. SMAP data for other JVM languages) is always `null` for
    // this compiler -- real per-class debug info (source file name for stack traces and a JVM
    // debugger's own source lookup).
    public static void visitSource(ClassWriter cw, String source) {
        cw.visitSource(source, null);
    }

    // A fourth instance of the same real "needs a genuine Java null" gap: an annotation array
    // element (`AnnotationVisitor.visit`/`.visitEnum`'s own `name` param) is unnamed by definition
    // -- ASM's own convention for "this call is inside a `visitArray` block, not a top-level named
    // argument" is passing `null` for `name`, which HC's `null` literal can't do as a bare call
    // argument. Staying entirely on the Java side of that gap too, same as every other one above.
    public static void visitArrayString(AnnotationVisitor av, String value) {
        av.visit(null, value);
    }

    public static void visitArrayEnum(AnnotationVisitor av, String enumDescriptor, String value) {
        av.visitEnum(null, enumDescriptor, value);
    }

    // Two more instances of the same real "needs a genuine Java null" gap -- a class-literal or
    // nested-annotation array element is unnamed by definition too, same as the String/enum cases
    // right above. Added for Mixins support (see IDEAS.md's own "Mixins" entry): `@Mixin`'s own
    // `value` param is `Class<?>[]`, so `@Mixin(value = [Target.class])` needs this exact shape.
    public static void visitArrayClass(AnnotationVisitor av, String internalName) {
        av.visit(null, Type.getObjectType(internalName));
    }

    public static AnnotationVisitor visitArrayAnnotation(AnnotationVisitor av, String descriptor) {
        return av.visitAnnotation(null, descriptor);
    }
}
