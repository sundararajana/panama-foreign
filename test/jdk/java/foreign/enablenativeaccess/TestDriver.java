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

/**
 * @test id=jni_no_warn
 * @build jni_module/*
 * @run main/othervm --enable-native-access=jni_module jni_module/org.openjdk.foreigntest.JNIMain
 * @summary no warning for JNI access with --enable-native-access
 */

/**
 * @test id=jni_warn
 * @build jni_module/*
 * @run main/othervm --enable-native-access=jni_module jni_module/org.openjdk.foreigntest.JNIMain
 * @summary warning for JNI access without --enable-native-access
 */

/**
 * @test id=panama
 * @build panama_module/*
 * @run main/othervm --enable-native-access=panama_module panama_module/org.openjdk.foreigntest.PanamaMain
 * @summary with --enable-native-access access to specific module Panama unsafe API succeeds
 */

/**
 * @test id=panama_no_enable_native_access_fail
 * @build panama_module/*
 * @run main/othervm/fail panama_module/org.openjdk.foreigntest.PanamaMain
 * @summary without --enable-native-access access to Panama unsafe API fails
 */

/**
 * @test id=panama_no_all_module_blanket_native_access
 * @build panama_module/*
 * @run main/othervm/fail --enable-native-access=ALL-MODULE-PATH panama_module/org.openjdk.foreigntest.PanamaMain
 * @summary --enable-native-access does not work with ALL-MODULE-PATH
 */

/**
 * @test id=panama_no_unnamed_module_blanket_native_access
 * @build org.openjdk.foreigntest.PanamaMainUnnamedModule
 * @run main/othervm/fail --enable-native-access=ALL-UNNAMED org.openjdk.foreigntest.PanamaMainUnnamedModule
 * @summary --enable-native-access does not work with ALL-UNNAMED
 */

/**
 * @test id=panama_unnamed_module_package_specific_native_access
 * @build org.openjdk.foreigntest.PanamaMainUnnamedModule
 * @run main/othervm --enable-native-access=ALL-UNNAMED/org.openjdk.foreigntest org.openjdk.foreigntest.PanamaMainUnnamedModule
 * @summary --enable-native-access ALL-UNNAMED/package_name works
 */
