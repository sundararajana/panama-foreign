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

package jdk.internal.foreign.jimage;

// Generated by jextract

import java.lang.invoke.VarHandle;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryHandles;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.NativeScope;
import static jdk.incubator.foreign.CSupport.C_POINTER;

public final class Cpointer {
    private static VarHandle arrayHandle(MemoryLayout elemLayout, Class<?> elemCarrier) {
        return MemoryLayout.ofSequence(elemLayout)
            .varHandle(elemCarrier, MemoryLayout.PathElement.sequenceElement());
    }

    public static final MemoryLayout LAYOUT = C_POINTER;
    public static final Class<?> CARRIER = long.class;
    private static final VarHandle handle = MemoryHandles.asAddressVarHandle(LAYOUT.varHandle(CARRIER));
    private static final VarHandle arrayHandle = MemoryHandles.asAddressVarHandle(arrayHandle(LAYOUT, CARRIER));

    public static MemoryAddress asArray(MemoryAddress addr, int numPointers) {
        var seg = addr.segment();
        if (seg == null) {
            throw new IllegalArgumentException("no underlying segment for the address");
        }
        return seg.asSlice(addr.segmentOffset(), numPointers * LAYOUT.byteSize()).baseAddress();
    }

    public static MemoryAddress get(MemoryAddress addr) {
        return (MemoryAddress) handle.get(addr);
    }

    public static void set(MemoryAddress addr, MemoryAddress value) {
        handle.set(addr, value);
    }

    public static MemoryAddress get(MemoryAddress addr, long index) {
        return (MemoryAddress) arrayHandle.get(addr, index);
    }

    public static void set(MemoryAddress addr, long index, MemoryAddress value) {
        arrayHandle.set(addr, index, value);
    }

    public static MemorySegment allocate(MemoryAddress value) {
        var seg = MemorySegment.allocateNative(LAYOUT);
        handle.set(seg.baseAddress(), value);
        return seg;
    }

    public static MemoryAddress allocate(MemoryAddress value, NativeScope scope) {
        var addr = scope.allocate(LAYOUT);
        handle.set(addr, value);
        return addr;
    }

    public static MemorySegment allocateArray(int length) {
        var arrLayout = MemoryLayout.ofSequence(length, LAYOUT);
        return MemorySegment.allocateNative(arrLayout);
    }

    public static MemoryAddress allocateArray(int length, NativeScope scope) {
        var arrLayout = MemoryLayout.ofSequence(length, LAYOUT);
        return scope.allocate(arrLayout);
    }

    public static long sizeof() {
        return LAYOUT.byteSize();
    }
}
