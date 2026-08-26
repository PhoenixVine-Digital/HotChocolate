package hc.selfhost.codegen;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

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
        try (OutputStream out = Files.newOutputStream(Paths.get(path))) {
            out.write(bytes);
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
}
