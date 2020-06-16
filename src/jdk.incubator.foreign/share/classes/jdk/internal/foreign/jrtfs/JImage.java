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

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class JImage {
    private JImage() {}

    public class ImageLocation {
        private final long id;
        private final String name;
        private final long size;

        public ImageLocation(long id, String name, long size) {
            this.id = id;
            this.name = name;
            this.size = size;
        }

        public String getName() {
            return name;
        }

        public long getSize() {
            return size;
        }

        public long getId() {
            return id;
        }
    }

    // jimage file does not store directory structure. We build nodes
    // using the "path" strings found in the jimage file.
    // Node can be a directory or a resource
    public abstract static class Node {
        private static final int ROOT_DIR = 0b0000_0000_0000_0001;
        private static final int PACKAGES_DIR = 0b0000_0000_0000_0010;
        private static final int MODULES_DIR = 0b0000_0000_0000_0100;

        private int flags;
        private final String name;
        private final BasicFileAttributes fileAttrs;
        private boolean completed;

        protected Node(String name, BasicFileAttributes fileAttrs) {
            this.name = Objects.requireNonNull(name);
            this.fileAttrs = Objects.requireNonNull(fileAttrs);
        }

        /**
         * A node is completed when all its direct children have been built.
         *
         * @return
         */
        public boolean isCompleted() {
            return completed;
        }

        public void setCompleted(boolean completed) {
            this.completed = completed;
        }

        public final void setIsRootDir() {
            flags |= ROOT_DIR;
        }

        public final boolean isRootDir() {
            return (flags & ROOT_DIR) != 0;
        }

        public final void setIsPackagesDir() {
            flags |= PACKAGES_DIR;
        }

        public final boolean isPackagesDir() {
            return (flags & PACKAGES_DIR) != 0;
        }

        public final void setIsModulesDir() {
            flags |= MODULES_DIR;
        }

        public final boolean isModulesDir() {
            return (flags & MODULES_DIR) != 0;
        }

        public final String getName() {
            return name;
        }

        public final BasicFileAttributes getFileAttributes() {
            return fileAttrs;
        }

        // resolve this Node (if this is a soft link, get underlying Node)
        public final Node resolveLink() {
            return resolveLink(false);
        }

        public Node resolveLink(boolean recursive) {
            return this;
        }

        // is this a soft link Node?
        public boolean isLink() {
            return false;
        }

        public boolean isDirectory() {
            return false;
        }

        public List<Node> getChildren() {
            throw new IllegalArgumentException("not a directory: " + getNameString());
        }

        public boolean isResource() {
            return false;
        }

        public ImageLocation getLocation() {
            throw new IllegalArgumentException("not a resource: " + getNameString());
        }

        public long size() {
            return 0L;
        }

        public long compressedSize() {
            return 0L;
        }

        public String extension() {
            return null;
        }

        public long contentOffset() {
            return 0L;
        }

        public final FileTime creationTime() {
            return fileAttrs.creationTime();
        }

        public final FileTime lastAccessTime() {
            return fileAttrs.lastAccessTime();
        }

        public final FileTime lastModifiedTime() {
            return fileAttrs.lastModifiedTime();
        }

        public final String getNameString() {
            return name;
        }

        @Override
        public final String toString() {
            return getNameString();
        }

        @Override
        public final int hashCode() {
            return name.hashCode();
        }

        @Override
        public final boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (other instanceof Node) {
                return name.equals(((Node) other).name);
            }

            return false;
        }
    }

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

        private Resource(ImageLocation loc, BasicFileAttributes fileAttrs) {
            super(loc.getName(), fileAttrs);
            this.loc = loc;
        }

        static Resource create(Directory parent, ImageLocation loc, BasicFileAttributes fileAttrs) {
            Resource rs = new Resource(loc, fileAttrs);
            parent.addChild(rs);
            return rs;
        }

        @Override
        public boolean isCompleted() {
            return true;
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

        @Override
        public long compressedSize() {
            return -1; // FIXME loc.getCompressedSize();
        }

        @Override
        public String extension() {
            return ""; // FIXME loc.getExtension();
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
        public boolean isCompleted() {
            return true;
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
}

