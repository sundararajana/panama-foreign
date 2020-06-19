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

import jdk.incubator.foreign.CSupport;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.NativeScope;
import jdk.internal.foreign.jimage.Cchar;
import jdk.internal.foreign.jimage.Cint;
import jdk.internal.foreign.jimage.Clong_long;
import sun.security.action.GetPropertyAction;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import static jdk.incubator.foreign.MemoryAddress.NULL;
import static jdk.incubator.foreign.CSupport.toCString;
import static jdk.incubator.foreign.CSupport.toJavaStringRestricted;
import static jdk.internal.foreign.jimage.jimage_h.*;

public class ModulesFileImage extends SystemImage {
    private static boolean DEBUG = Boolean.parseBoolean(GetPropertyAction.privilegedGetProperty("jimage.jrtfs.debug"));

    // directory node - directory has full path name without '/' at end.
    static final class Directory extends Node {
        private final List<Node> children;

        private Directory(String name, BasicFileAttributes fileAttrs) {
            super(name, fileAttrs);
            children = new ArrayList<>();
        }

        static Directory create(Directory parent, String name, BasicFileAttributes fileAttrs) {
            Directory d = new Directory(name, fileAttrs);
            if (parent != null) {
                parent.addChild(d);
            }
            return d;
        }

        @Override
        public boolean isDirectory() {
            return true;
        }

        @Override
        public List<Node> getChildren() {
            return Collections.unmodifiableList(children);
        }

        void addChild(Node node) {
            children.add(node);
        }

        public void walk(Consumer<? super Node> consumer) {
            consumer.accept(this);
            for (Node child : children) {
                if (child.isDirectory()) {
                    ((Directory) child).walk(consumer);
                } else {
                    consumer.accept(child);
                }
            }
        }
    }

    // "resource" is .class or any other resource (compressed/uncompressed) in a jimage.
    // full path of the resource is the "name" of the resource.
    static class Resource extends Node {
        private final ImageLocation loc;

        private Resource(String name, BasicFileAttributes fileAttrs, ImageLocation loc) {
            super(name, fileAttrs);
            this.loc = loc;
        }

        static Resource create(Directory parent, String name, BasicFileAttributes fileAttrs, ImageLocation loc) {
            Resource rs = new Resource(name, fileAttrs, loc);
            parent.addChild(rs);
            return rs;
        }

        @Override
        public boolean isResource() {
            return true;
        }

        @Override
        public ImageLocation getLocation() {
            return loc;
        }

        @Override
        public long size() {
            return loc.getSize();
        }
    }

    // represents a soft link to another Node
    static class LinkNode extends Node {
        private final Node link;

        private LinkNode(String name, Node link) {
            super(name, link.getFileAttributes());
            this.link = link;
        }

        static LinkNode create(Directory parent, String name, Node link) {
            LinkNode ln = new LinkNode(name, link);
            parent.addChild(ln);
            return ln;
        }

        @Override
        public Node resolveLink(boolean recursive) {
            return (recursive && link instanceof LinkNode) ? ((LinkNode) link).resolveLink(true) : link;
        }

        @Override
        public boolean isLink() {
            return true;
        }
    }

    private final MemoryAddress jimage;
    private final Directory root;
    private final Directory packagesDir;
    private final Directory modulesDir;
    private volatile boolean closed;

    // attributes of the .jimage file. jimage file does not contain
    // attributes for the individual resources (yet). We use attributes
    // of the jimage file itself (creation, modification, access times).
    // Iniitalized lazily, see {@link #imageFileAttributes()}.
    private final BasicFileAttributes imageFileAttributes;

    // directory management implementation
    private final Map<String, Node> nodes;

    ModulesFileImage(Path moduleImageFile) throws IOException {
        String fileName = moduleImageFile.toAbsolutePath().toString();
        jimage = openJImageFile(fileName);
        imageFileAttributes = Files.readAttributes(moduleImageFile, BasicFileAttributes.class);

        // initialize file system Nodes
        nodes = new HashMap<>();
        root = newDirectory(null, "/");

        // /packages dir
        packagesDir = newDirectory(root, "/packages");

        // /modules dir
        modulesDir = newDirectory(root, "/modules");
        initNodes();
    }

    @Override
    public Node findNode(String path) throws IOException {
        ensureOpen();
        return nodes.get(path);
    }

    @Override
    public byte[] getResource(Node node) throws IOException {
        ensureOpen();
        if (! node.isResource()) {
            throw new FileSystemException(node.getName() + " is not file");
        }

        ImageLocation loc = node.getLocation();
        long id = loc.getId();
        long size = loc.getSize();
        try (MemorySegment seg = Cchar.allocateArray((int)size)) {
            if (DEBUG) {
                System.err.println("DEBUG: reading resource: " + node.getName());
            }
            long res = JIMAGE_GetResource(jimage, loc.getId(), seg.baseAddress(), loc.getSize());
            if (res == JIMAGE_NOT_FOUND()) {
                throw new RuntimeException("resource " + node.getName() + " not found!");
            }
            byte[] content = Cchar.toJavaArray(seg);
            if (DEBUG) {
                System.err.println("DEBUG: done reading resource: " + node.getName());
            }
            return content;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            throw new IOException("image file already closed");
        }
        if (DEBUG) {
            System.out.println("DEBUG: Closing jimage file");
        }
        JIMAGE_Close(jimage);
        if (DEBUG) {
            System.out.println("DEBUG: jimage file closed");
        }
        closed = true;
    }

    // Internals only below this point

    private static void checkJImageOpenResult(int code) {
        String msg = null;
        if (code == JIMAGE_BAD_MAGIC()) {
            msg = "bad jimage file magic";
        } else if (code == JIMAGE_BAD_VERSION()) {
            msg = "bad jimage file version";
        } else if (code == JIMAGE_CORRUPTED()) {
            msg = "jimage file corrupted";
        }

        if (msg != null) {
            throw new RuntimeException("Jimage File Open Failed: " + msg);
        }
    }

    private static MemoryAddress openJImageFile(String fileName) {
        try (NativeScope scope = NativeScope.boundedScope(fileName.length() + 4 + Cint.sizeof())) {
            MemoryAddress nameAddr = CSupport.toCString(fileName, scope);
            MemoryAddress resAddr = Cint.allocate(0, scope);
            if (DEBUG) {
                System.err.println("DEBUG: Opening jimage file " + fileName);
            }
            MemoryAddress jimage = JIMAGE_Open(nameAddr, resAddr);
            checkJImageOpenResult(Cint.get(resAddr));
            return jimage;
        }
    }

    private void initNodes() throws IOException {
        // const char* module_name, const char* version, const char* package,
        // const char* res_name, const char* extension, void* arg
        try (NativeScope scope = NativeScope.unboundedScope()) {
            MemoryAddress sizePtr = Clong_long.allocate(0L, scope);
            if (DEBUG) {
                System.err.println("DEBUG: Iterating resources from jimage file");
            }
            MemoryAddress visitor = JIMAGE_ResourceIterator$visitor.allocate(
                (jimage, module_name, version, package_name, res_name, extension, arg) -> {
                    String modName = toJavaStringRestricted(module_name);
                    String pkgName = toJavaStringRestricted(package_name);
                    if (pkgName.isEmpty()) {
                        return 1; // FIXME Why empty package name?
                    }
                    String resName = toJavaStringRestricted(res_name);
                    String ext = toJavaStringRestricted(extension);
                    Directory modDir = findModuleDir(modName);
                    Directory pkgDir = findPackageDir(modDir, pkgName);
                    createPackageModuleLink(modName, pkgName, modDir);
                    Clong_long.set(sizePtr, 0);
                    String fullResName  = String.format("%s/%s.%s", pkgName, resName, ext);
                    MemoryAddress fullResNamePtr = toCString(fullResName, scope);
                    long id = JIMAGE_FindResource(jimage, module_name, version, fullResNamePtr, sizePtr);
                    // FIXME: possible to have zero sizes resource?
                    if (Clong_long.get(sizePtr) != 0L) {
                        String name = getFullResourceName(modName, pkgName, resName, ext);
                        ImageLocation loc = new ImageLocation(id, Clong_long.get(sizePtr));
                        newResource(pkgDir, name, loc);
                    }
                    return 1;
                }, scope);
            JIMAGE_ResourceIterator(jimage, visitor, NULL);
            if (DEBUG) {
                System.err.println("DEBUG: Resource iteration from jimage file done");
            }
        }
    }

    private String getFullResourceName(String modName, String pkgName, String resName, String ext) {
        return String.format("%s/%s/%s/%s.%s", modulesDir.getName(), modName, pkgName, resName, ext);
    }

    private Directory findModuleDir(String modName) {
        String fullName = String.format("%s/%s", modulesDir.getName(), modName);
        if (nodes.containsKey(fullName)) {
            return (Directory)nodes.get(fullName);
        } else {
            return newDirectory(modulesDir, fullName);
        }
    }

    private Directory findPackageDir(Directory modDir, String pkgName) {
        String fullName = String.format("%s/%s", modDir.getName(), pkgName);
        if (nodes.containsKey(fullName)) {
            return (Directory)nodes.get(fullName);
        } else {
            return makeDirectories(fullName);
        }
    }

    private void createPackageModuleLink(String modName, String pkgName, Directory modDir) {
        for (int offset = pkgName.indexOf('/');
                offset != -1;
                offset = pkgName.indexOf('/', offset + 1)) {
            String pkgPrefix = pkgName.substring(0, offset).replace('/', '.');
            String dirName = String.format("%s/%s", packagesDir.getName(), pkgPrefix);
            Directory dir = makeDirectory(dirName, packagesDir);
            String linkName = String.format("%s/%s", dirName, modName);
            if (!nodes.containsKey(linkName)) {
                newLinkNode(dir, linkName, modDir);
            }
        }
    }

    private Directory makeDirectories(String parent) {
        Directory last = root;
        for (int offset = parent.indexOf('/', 1);
                 offset != -1;
                 offset = parent.indexOf('/', offset + 1)) {
            String dir = parent.substring(0, offset);
            last = makeDirectory(dir, last);
        }
        return makeDirectory(parent, last);
    }

    private Directory makeDirectory(String dir, Directory last) {
        Directory nextDir = (Directory) nodes.get(dir);
        if (nextDir == null) {
            nextDir = newDirectory(last, dir);
        }
        return nextDir;
    }

    private Directory newDirectory(Directory parent, String name) {
        Directory dir = Directory.create(parent, name, imageFileAttributes);
        nodes.put(dir.getName(), dir);
        return dir;
    }

    private Resource newResource(Directory parent, String name, ImageLocation loc) {
        Resource res = Resource.create(parent, name, imageFileAttributes, loc);
        nodes.put(res.getName(), res);
        return res;
    }

    private LinkNode newLinkNode(Directory dir, String name, Node link) {
        LinkNode linkNode = LinkNode.create(dir, name, link);
        nodes.put(linkNode.getName(), linkNode);
        return linkNode;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("image file closed");
        }
    }
}
