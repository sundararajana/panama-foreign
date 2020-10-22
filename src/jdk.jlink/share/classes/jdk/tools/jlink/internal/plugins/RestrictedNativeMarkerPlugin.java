/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.tools.jlink.internal.plugins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.stream.Collectors;

import jdk.internal.org.objectweb.asm.Attribute;
import jdk.internal.org.objectweb.asm.ByteVector;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

import static jdk.internal.org.objectweb.asm.Opcodes.*;

import jdk.tools.jlink.plugin.PluginException;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolEntry;

/**
 * Jlink plugin makes modules that use RestrictedNative, RestrictedJNI methods.
 */
public final class RestrictedNativeMarkerPlugin extends AbstractPlugin {
    private static final boolean DEBUG = Boolean.getBoolean("jlink.restricted_native_marker.debug");
    private static final String RESTRICTED_NATIVE_METHODS_FILE = "restricted_native_methods.txt";

    // info on restricted methods
    private List<RestrictedMethod> restrictedMethods;
    // modules that use Panama RestrictedNative methods
    private Set<String> restrictedPanamaModules = new HashSet<>();
    // modules that use Panama RestrictedJNI methods
    private Set<String> restrictedJNIModules = new HashSet<>();

    public RestrictedNativeMarkerPlugin() {
        super("restricted-native-marker");
    }

    @Override
    public Set<State> getState() {
        return EnumSet.of(State.AUTO_ENABLED, State.FUNCTIONAL);
    }

    @Override
    public boolean hasArguments() {
        return true;
    }

    @Override
    public void configure(Map<String, String> config) {
        String mainArgument = config.get(getName());
        // Load configuration from the contents in the supplied input file
        // - if none was supplied we look for the default file
        if (mainArgument == null || !mainArgument.startsWith("@")) {
            try (InputStream traceFile =
                         this.getClass().getResourceAsStream(RESTRICTED_NATIVE_METHODS_FILE)) {
                restrictedMethods = new BufferedReader(new InputStreamReader(traceFile)).
                        lines().map(RestrictedMethod::new).collect(Collectors.toList());
            } catch (Exception e) {
                throw new PluginException("Couldn't read " + RESTRICTED_NATIVE_METHODS_FILE, e);
            }
        } else {
            File file = new File(mainArgument.substring(1));
            restrictedMethods = fileLines(file);
        }

        if (DEBUG) {
            System.err.println("====== Restricted methods start ======");
            for (RestrictedMethod rm : restrictedMethods) {
                rm.print();
            }
            System.err.println("====== Restricted methods end ======");
        }
    }

    private List<RestrictedMethod> fileLines(File file) {
        try {
            return Files.lines(file.toPath()).map(RestrictedMethod::new).collect(Collectors.toList());
        } catch (IOException io) {
            throw new PluginException("Couldn't read file");
        }
    }

    @Override
    public ResourcePool transform(ResourcePool in, ResourcePoolBuilder out) {
        // pass through all resources other than "module-info.class"es
        in.entries()
                .filter(data -> !data.path().endsWith("/module-info.class"))
                .forEach(data -> checkNative(data, out));

        // if a module is both panama and JNI, make sure
        restrictedJNIModules.removeAll(restrictedPanamaModules);

        if (DEBUG) {
            System.err.printf("restricted panama modules: %s\n", restrictedPanamaModules);
            System.err.printf("restricted jni modules: %s\n", restrictedJNIModules);
        }

        // transform (if needed), and add the module-info.class files
        transformModuleInfos(in, out);

        return out.build();
    }

    private void checkNative(ResourcePoolEntry data, ResourcePoolBuilder out) {
        out.add(data);
        String moduleName = data.moduleName();
        if (isRestrictedPanama(moduleName)) {
            // already detected to be restricted panama module. No need to check
            // further resources.
            if (DEBUG) {
                System.err.printf("module %s marked, skipping %s\n", moduleName, data.path());
            }
            return;
        }

        // check only .class resources
        if (data.type().equals(ResourcePoolEntry.Type.CLASS_OR_RESOURCE) &&
                data.path().endsWith(".class")) {
            NativeLevel nl = findNativeLevel(data.contentBytes());
            switch (nl) {
                case JNI:
                    if (DEBUG) {
                        System.err.printf("module %s is %s due to %s\n", moduleName, nl, data.path());
                    }
                    restrictedJNIModules.add(moduleName);
                    break;
                case PANAMA:
                    if (DEBUG) {
                        System.err.printf("module %s is %s due to %s\n", moduleName, nl, data.path());
                    }
                    restrictedPanamaModules.add(moduleName);
                    break;
                default:
                    break;
            }
        }
    }

    private boolean isRestrictedPanama(String moduleName) {
        return restrictedPanamaModules.contains(moduleName);
    }

    // find the native level of the given .class resource
    private NativeLevel findNativeLevel(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        NativeLevel[] nl = new NativeLevel[1];
        nl[0] = NativeLevel.NONE;

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM7) {
            @Override
            public MethodVisitor visitMethod(int access,
                                             String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ((access & ACC_NATIVE) == 0 && (access & ACC_ABSTRACT) == 0) {
                    return new MethodVisitor(Opcodes.ASM7,
                            super.visitMethod(access, name, descriptor,
                                    signature, exceptions)) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner,
                                                    String name, String descriptor, boolean isInterface) {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            NativeLevel nlTemp = findNativeLevel(owner, name, descriptor);
                            switch (nlTemp) {
                                case NONE:
                                    break;
                                case JNI:
                                    // if already JNI or PANAMA, nothing to do
                                    // Note that we don't want to overwrite a PANAMA resource
                                    // as JNI resource as later is for less severe (warning vs error)
                                    if (nl[0] == NativeLevel.NONE) {
                                        nl[0] = nlTemp;
                                    }
                                    break;
                                case PANAMA:
                                    nl[0] = nlTemp;
                                    break;
                            }
                        }
                    };
                } else {
                    return null;
                }
            }
        };

        reader.accept(cv, 0);
        return nl[0];
    }

    // find the native level of a specific call - check against known restricted methods
    private NativeLevel findNativeLevel(String owner, String name, String descriptor) {
        for (RestrictedMethod rm : restrictedMethods) {
            if (rm.match(owner, name, descriptor)) {
                return rm.panama ? NativeLevel.PANAMA : NativeLevel.JNI;
            }
        }
        return NativeLevel.NONE;
    }

    /**
     * Transforms the module-info.class files in the modules, marking native or not.
     */
    private void transformModuleInfos(ResourcePool in, ResourcePoolBuilder out) {
        in.moduleView().modules().forEach(module -> {
            ResourcePoolEntry data = module.findEntry("module-info.class").orElseThrow(
                    // FIXME: automatic modules not supported yet
                    // add something in META-INFO?
                    () -> new PluginException("module-info.class not found for " +
                            module.name() + " module")
            );

            assert module.name().equals(data.moduleName());

            String moduleName = data.moduleName();
            boolean isPanama = restrictedPanamaModules.contains(moduleName);
            boolean isJNI = restrictedJNIModules.contains(moduleName);
            if (isPanama || isJNI) {
                // add a class level attribute if we found a panama or JNI method
                // call from the currently visited module
                ClassReader reader = new ClassReader(data.contentBytes());
                ClassWriter cw = new ClassWriter(reader, 0);
                ClassVisitor cv = new ClassVisitor(Opcodes.ASM7, cw) {
                    @Override
                    public void visitEnd() {
                        cw.visitAttribute(newAttribute(isPanama ? "RestrictedNative" : "RestrictedJNI"));
                        super.visitEnd();
                    }
                };

                reader.accept(cv, 0);

                // add resource pool entry
                out.add(data.copyWithContent(cw.toByteArray()));
            } else {
                // not a native module. copy module-info 'as is'
                out.add(data);
            }
        });
    }

    // empty .class attribute of given name
    private Attribute newAttribute(String name) {
        return new Attribute(name) {
            @Override
            protected ByteVector write(
                    final ClassWriter classWriter,
                    final byte[] code,
                    final int codeLength,
                    final int maxStack,
                    final int maxLocals) {
                return new ByteVector();
            }
        };
    }

    // info about a restricted method
    private static class RestrictedMethod {
        final String className;
        final String methodName;
        final String methodDesc;
        final boolean panama;

        RestrictedMethod(String line) {
            String[] parts = line.split(" ");
            this.className = parts[0];
            this.methodName = parts[1];
            this.methodDesc = parts[2];
            this.panama = Boolean.parseBoolean(parts[3]);
        }

        void print() {
            System.err.printf("Restricted method: %s %s %s %s\n", className, methodName,
                    methodDesc, panama ? "PANAMA" : "JNI");
        }

        boolean match(String owner, String name, String descriptor) {
            return className.equals(owner) && methodName.equals(name) && methodDesc.equals(descriptor);
        }
    }

    private enum NativeLevel {
        NONE, JNI, PANAMA
    }
}
