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
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.NativeScope;
import static jdk.incubator.foreign.CSupport.C_INT;

public class Cint {
    // don't create!
    Cint() {
    }

    private static VarHandle arrayHandle(MemoryLayout elemLayout, Class<?> elemCarrier) {
        return MemoryLayout.ofSequence(elemLayout)
                 .varHandle(elemCarrier, MemoryLayout.PathElement.sequenceElement());
    }

    public static final MemoryLayout LAYOUT = C_INT;
    public static final Class<?> CARRIER = int.class;
    private static final VarHandle handle = LAYOUT.varHandle(CARRIER);
    private static final VarHandle arrayHandle = arrayHandle(LAYOUT, CARRIER);

    public static MemoryAddress asArrayRestricted(MemoryAddress addr, int numElements) {
        return MemorySegment.ofNativeRestricted(addr, numElements * LAYOUT.byteSize(),
               Thread.currentThread(), null, null).baseAddress();
    }

    public static MemoryAddress asArray(MemoryAddress addr, int numElements) {
        var seg = addr.segment();
        if (seg == null) {
            throw new IllegalArgumentException("no underlying segment for the address");
        }
        return seg.asSlice(addr.segmentOffset(), numElements * LAYOUT.byteSize()).baseAddress();
    }

    public static int get(MemoryAddress addr) {
        return (int) handle.get(addr);
    }

    public static void set(MemoryAddress addr, int value) {
        handle.set(addr, value);
    }

    public static int get(MemoryAddress addr, long index) {
        return (int) arrayHandle.get(addr, index);
    }

    public static void set(MemoryAddress addr, long index, int value) {
        arrayHandle.set(addr, index, value);
    }

    public static MemorySegment allocate(int value) {
        var seg = MemorySegment.allocateNative(LAYOUT);
        handle.set(seg.baseAddress(), value);
        return seg;
    }

    public static MemoryAddress allocate(int value, NativeScope scope) {
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

    public static MemorySegment allocateArray(int[] arr) {
        var arrLayout = MemoryLayout.ofSequence(arr.length, LAYOUT);
        var seg = MemorySegment.allocateNative(arrLayout);
        seg.copyFrom(MemorySegment.ofArray(arr));
        return seg;
    }

    public static MemoryAddress allocateArray(int[] arr, NativeScope scope) {
        var arrLayout = MemoryLayout.ofSequence(arr.length, LAYOUT);
        var addr = scope.allocate(arrLayout);
        addr.segment().copyFrom(MemorySegment.ofArray(arr));
        return addr;
    }

    public static long sizeof() {
        return LAYOUT.byteSize();
    }

    public static int[] toJavaArray(MemorySegment seg) {
        var segSize = seg.byteSize();
        var elemSize = sizeof();
        if (segSize % elemSize != 0) {
            throw new UnsupportedOperationException("segment cannot contain integral number of elements");
        }
        int[] array = new int[(int) (segSize / elemSize)];
        MemorySegment.ofArray(array).copyFrom(seg);
        return array;
    }
}
