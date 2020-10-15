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

package build.tools.panama;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskEvent.Kind;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

public class RestrictedMethodsFinder implements Plugin {
    private static final String RESTRICTED_NATIVE = "jdk.internal.vm.annotation.RestrictedNative";
    private static final String RESTRICTED_JNI = "jdk.internal.vm.annotation.RestrictedJNI";

    @Override
    public void init(JavacTask task, String... args) {
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent e) {
                if (e.getKind() == Kind.ANALYZE) {
                    Trees trees = Trees.instance(task);
                    CompilationUnitTree cut = e.getCompilationUnit();
                    new TreePathScanner<Void, Void>() {
                        @Override
                        public Void visitMethod(MethodTree node, Void p) {
                            Element el = trees.getElement(getCurrentPath());
                            if (el != null) {
                                if (isRestrictedNative(el) || isRestrictedJNI(el)) {
                                    trees.printMessage(Diagnostic.Kind.NOTE, "Found a method marked with @RestrictedNative/JNI", node, cut);
                                }
                            }
                            return super.visitMethod(node, p);
                        }
                    }.scan(cut, null);
                }
            }
        });
    }

    private boolean isRestrictedNative(Element el) {
        return checkAnnotation(el, RESTRICTED_NATIVE);
    }

    private boolean isRestrictedJNI(Element el) {
        return checkAnnotation(el, RESTRICTED_JNI);
    }

    private boolean checkAnnotation(Element el, String name) {
        return el.getAnnotationMirrors().stream().anyMatch(
            am -> ((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().contentEquals(name));
    }

    @Override
    public String getName() {
        return "panama";
    }

    public boolean autoStart() {
        return false;
    }
}
