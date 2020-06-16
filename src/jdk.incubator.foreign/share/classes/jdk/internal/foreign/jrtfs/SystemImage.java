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
package jdk.internal.foreign.jrtfs;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.PrivilegedAction;

import jdk.incubator.foreign.CSupport;
import jdk.incubator.foreign.MemoryAddress;
import jdk.internal.foreign.jrtfs.JImage.Node;
import static jdk.internal.foreign.jimage.jimage_h.*;

abstract class SystemImage {

    abstract Node findNode(String path) throws IOException;
    abstract byte[] getResource(Node node) throws IOException;
    abstract void close() throws IOException;

    static SystemImage open() throws IOException {
        if (modulesImageExists) {
            // open a .jimage and build directory structure
            var nameSeg = CSupport.toCString(moduleImageFile.toAbsolutePath().toString());
            var jimage = JIMAGE_Open(nameSeg.baseAddress(), MemoryAddress.NULL);
            nameSeg.close();
            // FIXME image.getRootDirectory();
            return new SystemImage() {
                @Override
                Node findNode(String path) throws IOException {
                    return null; // FIXME image.findNode(path);
                }
                @Override
                byte[] getResource(Node node) throws IOException {
                    return null; // FIXME image.getResource(node);
                }
                @Override
                void close() throws IOException {
                    JIMAGE_Close(jimage);
                }
            };
        }
        if (Files.notExists(explodedModulesDir))
            throw new FileSystemNotFoundException(explodedModulesDir.toString());
        return new ExplodedImage(explodedModulesDir);
    }

    static final String RUNTIME_HOME;
    // "modules" jimage file Path
    final static Path moduleImageFile;
    // "modules" jimage exists or not?
    final static boolean modulesImageExists;
    // <JAVA_HOME>/modules directory Path
    static final Path explodedModulesDir;

    static {
        PrivilegedAction<String> pa = SystemImage::findHome;
        RUNTIME_HOME = AccessController.doPrivileged(pa);

        FileSystem fs = FileSystems.getDefault();
        moduleImageFile = fs.getPath(RUNTIME_HOME, "lib", "modules");
        explodedModulesDir = fs.getPath(RUNTIME_HOME, "modules");

        modulesImageExists = AccessController.doPrivileged(
            new PrivilegedAction<Boolean>() {
                @Override
                public Boolean run() {
                    return Files.isRegularFile(moduleImageFile);
                }
            });
    }

    /**
     * Returns the appropriate JDK home for this usage of the FileSystemProvider.
     * When the CodeSource is null (null loader) then jrt:/ is the current runtime,
     * otherwise the JDK home is located relative to jrt-fs.jar.
     */
    private static String findHome() {
        CodeSource cs = SystemImage.class.getProtectionDomain().getCodeSource();
        if (cs == null)
            return System.getProperty("java.home");

        // assume loaded from $TARGETJDK/lib/jrt-fs.jar
        URL url = cs.getLocation();
        if (!url.getProtocol().equalsIgnoreCase("file"))
            throw new InternalError(url + " loaded in unexpected way");
        try {
            Path lib = Paths.get(url.toURI()).getParent();
            if (!lib.getFileName().toString().equals("lib"))
                throw new InternalError(url + " unexpected path");

            return lib.getParent().toString();
        } catch (URISyntaxException e) {
            throw new InternalError(e);
        }
    }
}
