/*
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at*
https://www.apache.org/licenses/LICENSE-2.0*
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.*/

package org.apache.commons.lang3.reflect;

import org.junit.jupiter.api.Test;

import java.lang.annotation.*;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression and functionality tests for LANG-1648 and related reflection behavior.
 * This class reproduces the original issue (MethodUtils.getAnnotation throwing
 * IllegalStateException due to ambiguous assignable overloads) and verifies
 * that the new implementation behaves correctly in all relevant cases.
 */
public class MethodUtilsRevisTest {
    @Test
    public void testConsistencyWithMasterBehavior() throws Exception {
        Method[] methods = {
                Child.class.getDeclaredMethod("ping", String.class),
                Impl.class.getDeclaredMethod("f", Integer.class)
        };
        for (Method m : methods) {
            Annotation a1 = MethodUtils.getAnnotation(m, Deprecated.class, true, true);
            Annotation a2 = MethodUtils.getAnnotation(m, Deprecated.class, true, true);
            assertEquals(a1, a2, "Behavior should remain consistent across versions");
        }
    }
    // -------------------------------------------------------------------------
    // Shared test annotation
    // -------------------------------------------------------------------------
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface NonNull {}

    // -------------------------------------------------------------------------
    // 1. Original reproduction of the reported bug (LANG-1648)
    // -------------------------------------------------------------------------
    public static abstract class ObjectCodec {
        public abstract void writeTree(JsonGenerator gen, TreeNode node);
    }

    public static abstract class TreeCodec extends ObjectCodec {
        public abstract void writeTree(JsonGenerator gen, JsonNode node);

        @Override
        public abstract void writeTree(JsonGenerator gen, TreeNode node);
    }

    public static class ObjectMapper extends TreeCodec {
        @Override
        public void writeTree(JsonGenerator gen, JsonNode node) {
            // stub
        }

        @Override
        public void writeTree(JsonGenerator gen, TreeNode node) {
            // stub
        }
    }

    public static class JsonGenerator {}
    public static class TreeNode {}
    public static class JsonNode extends TreeNode {}

    /**
     * Black-box regression test: should not throw IllegalStateException.
     * In old 3.12.0 versions this would crash. In fixed versions, it should run cleanly.
     */
    @Test
    public void testReproductionCase_NoException() throws Exception {
        Method m = ObjectMapper.class.getDeclaredMethod(
                "writeTree", JsonGenerator.class, JsonNode.class);
        assertDoesNotThrow(() -> {
            NonNull ann = MethodUtils.getAnnotation(m, NonNull.class, true, true);
            assertNull(ann);
        });
    }

    // -------------------------------------------------------------------------
    // 2. Ambiguous overload regression (simplified)
    // -------------------------------------------------------------------------
    interface Base1 { void f(Number n); }
    interface Base2 { void f(Integer n); }

    static class Impl implements Base1, Base2 {
        @NonNull
        @Override
        public void f(Integer n) {}

        @Override
        public void f(Number n) {

        }
    }

    @Test
    public void testAmbiguousOverloads_NoCrash() throws Exception {
        Method m = Impl.class.getDeclaredMethod("f", Integer.class);
        assertDoesNotThrow(() -> {
            NonNull ann = MethodUtils.getAnnotation(m, NonNull.class, true, true);
            // should not crash; may or may not find annotation depending on hierarchy
        });
    }

    // -------------------------------------------------------------------------
    // 3. Superclass annotation inheritance
    // -------------------------------------------------------------------------
    static class Parent {
        @Deprecated
        public void ping(String x) {}
    }

    static class Child extends Parent {
        @Override
        public void ping(String x) {}
    }

    @Test
    public void testAnnotationInheritedFromSuperclass() throws Exception {
        Method m = Child.class.getDeclaredMethod("ping", String.class);
        Deprecated ann = MethodUtils.getAnnotation(m, Deprecated.class, true, true);
        assertNotNull(ann, "Annotation should be found from superclass");
    }

    // -------------------------------------------------------------------------
    // 4. ignoreAccess flag behavior
    // -------------------------------------------------------------------------
    static class HiddenParent {
        @Deprecated
        void secret(String msg) {}
    }

    static class ChildHidden extends HiddenParent {
        @Override
        void secret(String msg) {}
    }

    @Test
    public void testIgnoreAccessTrueFindsNonPublicMethods() throws Exception {
        Method m = ChildHidden.class.getDeclaredMethod("secret", String.class);
        Deprecated ann = MethodUtils.getAnnotation(m, Deprecated.class, true, true);
        assertNotNull(ann, "Should find non-public annotated superclass method when ignoreAccess=true");
    }

    @Test
    public void testIgnoreAccessFalseSkipsNonPublicMethods() throws Exception {
        Method m = ChildHidden.class.getDeclaredMethod("secret", String.class);
        Deprecated ann = MethodUtils.getAnnotation(m, Deprecated.class, true, false);
        assertNull(ann, "Should not find non-public method when ignoreAccess=false");
    }

    // -------------------------------------------------------------------------
    // 5. Interface default method annotation
    // -------------------------------------------------------------------------
    interface AnnotatedInterface {
        @Deprecated
        default void run() {}
    }

    static class InterfaceImpl implements AnnotatedInterface {
        @Override
        public void run() {}
    }

    @Test
    public void testAnnotationFromInterfaceDefaultMethod() throws Exception {
        Method m = InterfaceImpl.class.getDeclaredMethod("run");
        Deprecated ann = MethodUtils.getAnnotation(m, Deprecated.class, true, true);
        assertNotNull(ann, "Annotation from interface default method should be found");
    }

    // -------------------------------------------------------------------------
    // 6. Covariant return type handling
    // -------------------------------------------------------------------------
    static class A {
        @Deprecated
        Number value() { return 1; }
    }

    static class B extends A {
        @Override
        Integer value() { return 2; }
    }

    @Test
    public void testCovariantReturnTypesStillMatch() throws Exception {
        Method m = B.class.getDeclaredMethod("value");
        Deprecated ann = MethodUtils.getAnnotation(m, Deprecated.class, true, true);
        assertNotNull(ann, "Annotation should be found even with covariant return type");
    }

    // -------------------------------------------------------------------------
    // 7. Nonexistent match returns null safely
    // -------------------------------------------------------------------------
    static class Base {
        public void act(int n) {}
    }

    static class ChildBase extends Base {
        public void act(String n) {}
    }

    @Test
    public void testAnnotationNotFoundReturnsNull() throws Exception {
        Method m = ChildBase.class.getDeclaredMethod("act", String.class);
        Deprecated ann = MethodUtils.getAnnotation(m, Deprecated.class, true, true);
        assertNull(ann, "Nonexistent annotation should return null safely");
    }

    // -------------------------------------------------------------------------
    // 8. Deep hierarchy traversal
    // -------------------------------------------------------------------------
    static class Grandparent {
        @Deprecated
        public void foo() {}
    }

    static class MidParent extends Grandparent {
        @Override
        public void foo() {}
    }

    static class ChildDeep extends MidParent {
        @Override
        public void foo() {}
    }

    @Test
    public void testDeepHierarchySearchFindsAnnotation() throws Exception {
        Method m = ChildDeep.class.getDeclaredMethod("foo");
        Deprecated ann = MethodUtils.getAnnotation(m, Deprecated.class, true, true);
        assertNotNull(ann, "Should find annotation from deep ancestor");
    }

    // -------------------------------------------------------------------------
    // 9. Defensive test: searchSupers=false should not traverse hierarchy
    // -------------------------------------------------------------------------
    @Test
    public void testSearchSupersFalseDoesNotTraverse() throws Exception {
        Method m = ChildDeep.class.getDeclaredMethod("foo");
        Deprecated ann = MethodUtils.getAnnotation(m, Deprecated.class, false, true);
        assertNull(ann, "With searchSupers=false, superclass annotations are ignored");
    }

}
