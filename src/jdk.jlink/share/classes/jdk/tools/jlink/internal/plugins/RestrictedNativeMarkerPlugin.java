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

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.ModuleVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import static jdk.internal.org.objectweb.asm.Opcodes.*;

import jdk.tools.jlink.internal.ModuleSorter;
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

    private List<RestrictedMethod> restrictedMethods;
    private Set<String> restrictedNativeModules = new HashSet<>();
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
            System.err.println("Restricted methods start");
            for (RestrictedMethod rm : restrictedMethods) {
                rm.print();
            }
            System.err.println("Restricted methods end");
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
        // pass through all other resources
        in.entries()
            .filter(data -> !data.path().endsWith("/module-info.class"))
            .forEach(data -> checkNative(data, out));
        // validate, transform (if needed), and add the module-info.class files
        transformModuleInfos(in, out);

        return out.build();
    }

    private void checkNative(ResourcePoolEntry data, ResourcePoolBuilder out) {
        out.add(data);
        if (isRestrictedNative(data.moduleName())) {
            return; // nothing to do. already detected it as native
        }
        if (data.type().equals(ResourcePoolEntry.Type.CLASS_OR_RESOURCE) &&
            data.path().endsWith(".class")) {
            NativeLevel nl = findNativeLevel(data.contentBytes());
            if (nl != NativeLevel.NONE) {
                if (DEBUG) {
                    System.err.println(data.path() + " native level " + nl);
                }
            }
        }
    }

    private boolean isRestrictedNative(String moduleName) {
        return restrictedNativeModules.contains(moduleName);
    }

    // find native level of a given .class resource
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
                                   if (nl[0] == NativeLevel.NONE) {
                                       nl[0] = nlTemp;
                                   }
                                   break;
                               case PANAMA:
                                   nl[0] = nlTemp;
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

    private NativeLevel findNativeLevel(String owner, String name, String descriptor) {
        for (RestrictedMethod rm : restrictedMethods) {
            if (rm.match(owner, name, descriptor)) {
                return rm.panama? NativeLevel.PANAMA : NativeLevel.JNI;
            }
        }
        return NativeLevel.NONE;
    }

    /**
     * Validates and transforms the module-info.class files in the modules, adding
     * the ModulePackages class file attribute if needed.
     */
    private void transformModuleInfos(ResourcePool in, ResourcePoolBuilder out) {
        // Sort modules in the topological order so that java.base is always first.
        new ModuleSorter(in.moduleView()).sorted().forEach(module -> {
            ResourcePoolEntry data = module.findEntry("module-info.class").orElseThrow(
                // automatic modules not supported
                () ->  new PluginException("module-info.class not found for " +
                        module.name() + " module")
            );

            assert module.name().equals(data.moduleName());

            //try {
                byte[] content = data.contentBytes();

                // add resource pool entry
                out.add(data);
            //} catch (IOException e) {
            //    throw new PluginException(e);
            //}
        });
    }

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
            System.err.printf("%s %s %s %b\n", className, methodName, methodDesc, panama);
        }

        boolean match(String owner, String name, String descriptor) {
            return className.equals(owner) && methodName.equals(name) && methodDesc.equals(descriptor);
        }
    }

    private enum NativeLevel {
        NONE, JNI, PANAMA
    }
}
