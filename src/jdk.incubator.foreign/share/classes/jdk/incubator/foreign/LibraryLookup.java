/*
 *  Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.  Oracle designates this
 *  particular file as subject to the "Classpath" exception as provided
 *  by Oracle in the LICENSE file that accompanied this code.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */
package jdk.incubator.foreign;

import jdk.internal.foreign.LibrariesHelper;

import java.io.File;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A native library lookup. Exposes a lookup operation for searching symbols, see {@link LibraryLookup#lookup(String)}.
 * A given native library remains loaded as long as there is at least one <em>live</em> library lookup instance referring
 * to it.
 * All symbol instances (see {@link LibraryLookup.Symbol}) generated by a given library lookup object contain a strong reference
 * to said lookup object, therefore preventing library unloading; in turn method handle instances obtained from
 * {@link CLinker#downcallHandle(Addressable, MethodType, FunctionDescriptor)}) also maintain a strong reference
 * to the addressable parameter used for their construction. This means that there is always a strong reachability chain
 * from a native method handle to a lookup object (the one that was used to lookup the native library symbol the method handle
 * refers to); this is useful to prevent situations where a native library is unloaded in the middle of a native call.
 * <p><a id = "var-symbols"></a></p>
 * In cases where a client wants to create a memory segment out of a lookup symbol, the client might want to attach the
 * lookup symbol to the newly created segment, so that the symbol will be kept reachable as long as the memory segment
 * is reachable; this can be achieved by creating the segment using the {@link MemoryAddress#asSegmentRestricted(long, ResourceScope)}.
 * restricted segment factory, as follows:
 * <pre>{@code
LibraryLookup defaultLookup = LibraryLookup.defaultLookup();
LibraryLookup.Symbol errno = defaultLookup.lookup("errno");
MemorySegment errnoSegment = errno.address().asSegmentRestricted(4, ResourceScope.ofShared(errno, Cleaner.create()));
 * }</pre>
 * <p>
 * To allow for a library to be unloaded, a client will have to discard any strong references it
 * maintains, directly, or indirectly to a lookup object associated with given library.
 *
 * <p> Unless otherwise specified, passing a {@code null} argument, or an array argument containing one or more {@code null}
 * elements to a method in this class causes a {@link NullPointerException NullPointerException} to be thrown. </p>
 */
public interface LibraryLookup {

    /**
     * A symbol retrieved during a library lookup. A lookup symbol has a <em>name</em> and can be projected
     * into a memory address (see {@link #name()} and {@link #address()}, respectively).
     *
     * @apiNote In the future, if the Java language permits, {@link Symbol}
     * may become a {@code sealed} interface, which would prohibit subclassing except by
     * explicitly permitted types.
     *
     * @implSpec
     * Implementations of this interface are immutable, thread-safe and <a href="{@docRoot}/java.base/java/lang/doc-files/ValueBased.html">value-based</a>.
     */
    interface Symbol extends Addressable {
        /**
         * The name of this lookup symbol.
         * @return the name of this lookup symbol.
         */
        String name();

        /**
         * The memory address of this lookup symbol. If the memory associated with this symbol needs to be dereferenced,
         * clients can obtain a segment from this symbol's address using the {@link MemoryAddress#asSegmentRestricted(long, Runnable, ResourceScope)},
         * and making sure that the created segment maintains a <a href="LibraryLookup.html#var-symbols">strong reference</a> to this symbol, to prevent library unloading.
         * @return the memory address of this lookup symbol.
         */
        @Override
        MemoryAddress address();
    }

    /**
     * Looks up a symbol with given name in this library. The returned symbol maintains a strong reference to this lookup object.
     * @param name the symbol name.
     * @return the library symbol (if any).
     */
    Optional<Symbol> lookup(String name);

    /**
     * Obtain a default library lookup object.
     * @return the default library lookup object.
     */
    static LibraryLookup ofDefault() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(new RuntimePermission("java.foreign.getDefaultLibrary"));
        }
        return LibrariesHelper.getDefaultLibrary();
    }

    /**
     * Obtain a library lookup object corresponding to a library identified by given path.
     * @param path the library absolute path.
     * @return a library lookup object for given path.
     * @throws IllegalArgumentException if the specified path does not correspond to an absolute path,
     * e.g. if {@code !path.isAbsolute()}.
     */
    static LibraryLookup ofPath(Path path) {
        Objects.requireNonNull(path);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Not an absolute path: " + path.toString());
        }
        String absolutePath = path.toString();
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkLink(absolutePath);
        }
        return LibrariesHelper.load(absolutePath);
    }

    /**
     * Obtain a library lookup object corresponding to a library identified by given library name. The library name
     * is decorated according to the platform conventions (e.g. on Linux, the {@code lib} prefix is added,
     * as well as the {@code .so} extension); the resulting name is then looked up in the standard native
     * library path (which can be overriden, by setting the <code>java.library.path</code> property).
     * @param libName the library name.
     * @return a library lookup object for given library name.
     */
    static LibraryLookup ofLibrary(String libName) {
        Objects.requireNonNull(libName);
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkLink(libName);
        }
        if (libName.indexOf(File.separatorChar) != -1) {
            throw new UnsatisfiedLinkError(
                    "Directory separator should not appear in library name: " + libName);
        }
        return LibrariesHelper.loadLibrary(libName);
    }
}
