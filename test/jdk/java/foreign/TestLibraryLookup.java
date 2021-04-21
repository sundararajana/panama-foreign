/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @requires ((os.arch == "amd64" | os.arch == "x86_64") & sun.arch.data.model == "64") | os.arch == "aarch64"
 * @modules jdk.incubator.foreign/jdk.internal.foreign
 * @run testng/othervm --enable-native-access=ALL-UNNAMED TestLibraryLookup
 */

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemoryLayouts;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

// FYI this test is run on 64-bit platforms only for now,
// since the windows 32-bit linker fails and there
// is some fallback behaviour to use the 64-bit linker,
// where cygwin gets in the way and we accidentally pick up its
// link.exe
public class TestLibraryLookup {

    private static CLinker abi = CLinker.getInstance();
    static {
        System.loadLibrary("LookupTest");
    }

    @Test
    public void testSimpleLookup() {
        assertNotEquals(abi.lookup("f"), MemoryAddress.NULL);
    }

    @Test
    public void testInvalidSymbolLookup() {
        assertEquals(abi.lookup("nonExistent"), MemoryAddress.NULL);
    }

    @Test
    public void testVariableSymbolLookup() throws Throwable {
        MemorySegment segment = abi.lookup("c").asSegment(MemoryLayouts.JAVA_INT.byteSize(), ResourceScope.globalScope());
        assertEquals(MemoryAccess.getInt(segment), 42);
    }

    @Test
    public void testBadVariableSymbolLookup() {
        try {
            MemoryLayout layout = MemoryLayouts.JAVA_INT.withBitAlignment(1 << 16);
            MemorySegment segment = abi.lookup("c").asSegment(layout.byteSize(), ResourceScope.globalScope());
            // no exception, check that address is aligned
            if ((segment.address().toRawLongValue() % layout.byteAlignment()) != 0) {
                fail("Unaligned address");
            }
        } catch (IllegalArgumentException ex) {
            // ok, means address was not aligned
        }

    }
}
