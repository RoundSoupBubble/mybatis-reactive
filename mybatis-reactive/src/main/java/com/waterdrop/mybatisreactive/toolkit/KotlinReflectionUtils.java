package com.waterdrop.mybatisreactive.toolkit;

import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.*;
import kotlin.reflect.jvm.KTypesJvm;
import kotlin.reflect.jvm.ReflectJvmMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

public class KotlinReflectionUtils {
    private KotlinReflectionUtils() {}

    /**
     * Return {@literal true} if the specified class is a supported Kotlin class. Currently supported are only regular
     * Kotlin classes. Other class types (synthetic, SAM, lambdas) are not supported via reflection.
     *
     * @return {@literal true} if {@code type} is a supported Kotlin class.
     */
    public static boolean isSupportedKotlinClass(Class<?> type) {

        if (!KotlinDetector.isKotlinType(type)) {
            return false;
        }

        return Arrays.stream(type.getDeclaredAnnotations()) //
                .filter(annotation -> annotation.annotationType().getName().equals("kotlin.Metadata")) //
                .map(annotation -> getAnnotationValue(annotation, "k")) //
                .anyMatch(it -> Integer.valueOf(KotlinClassHeaderKind.CLASS.id).equals(it));
    }

    private static Object getAnnotationValue(Annotation annotation, String attributeName) {
        if (annotation == null || !StringUtils.hasText(attributeName)) {
            return null;
        }
        try {
            Method method = annotation.annotationType().getDeclaredMethod(attributeName);
            makeAccessible(method);
            return method.invoke(annotation);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Could not obtain value for annotation attribute '" +
                    attributeName + "' in " + annotation, ex);
        } catch (Throwable ex) {
            return null;
        }
    }

    private static void makeAccessible(Method method) {
        if ((!Modifier.isPublic(method.getModifiers()) ||
                !Modifier.isPublic(method.getDeclaringClass().getModifiers())) && !method.isAccessible()) {
            method.setAccessible(true);
        }
    }


    /**
     * Return {@literal true} if the specified class is a Kotlin data class.
     *
     * @return {@literal true} if {@code type} is a Kotlin data class.
     * @since 2.5.1
     */
    public static boolean isDataClass(Class<?> type) {

        if (!KotlinDetector.isKotlinType(type)) {
            return false;
        }

        KClass<?> kotlinClass = JvmClassMappingKt.getKotlinClass(type);
        return kotlinClass.isData();
    }

    /**
     * Returns a {@link KFunction} instance corresponding to the given Java {@link Method} instance, or {@code null} if
     * this method cannot be represented by a Kotlin function.
     *
     * @param method the method to look up.
     * @return the {@link KFunction} or {@code null} if the method cannot be looked up.
     */
    public static KFunction<?> findKotlinFunction(Method method) {

        KFunction<?> kotlinFunction = ReflectJvmMapping.getKotlinFunction(method);

        // Fallback to own lookup because there's no public Kotlin API for that kind of lookup until
        // https://youtrack.jetbrains.com/issue/KT-20768 gets resolved.
        return kotlinFunction == null ? findKFunction(method).orElse(null) : kotlinFunction;
    }

    /**
     * Returns whether the {@link Method} is declared as suspend (Kotlin Coroutine).
     *
     * @param method the method to inspect.
     * @return {@literal true} if the method is declared as suspend.
     * @see KFunction#isSuspend()
     */
    public static boolean isSuspend(Method method) {

        KFunction<?> invokedFunction = KotlinDetector.isKotlinType(method.getDeclaringClass()) ? findKotlinFunction(method)
                : null;

        return invokedFunction != null && invokedFunction.isSuspend();
    }

    /**
     * Returns the {@link Class return type} of a Kotlin {@link Method}. Supports regular and suspended methods.
     *
     * @param method the method to inspect, typically any synthetic JVM {@link Method}.
     * @return return type of the method.
     */
    public static Class<?> getReturnType(Method method) {

        KFunction<?> kotlinFunction = KotlinReflectionUtils.findKotlinFunction(method);

        if (kotlinFunction == null) {
            throw new IllegalArgumentException(String.format("Cannot resolve %s to a KFunction!", method));
        }

        return JvmClassMappingKt.getJavaClass(KTypesJvm.getJvmErasure(kotlinFunction.getReturnType()));
    }

    /**
     * Lookup a {@link Method} to a {@link KFunction}.
     *
     * @param method the JVM {@link Method} to look up.
     * @return {@link Optional} wrapping a possibly existing {@link KFunction}.
     */
    private static Optional<? extends KFunction<?>> findKFunction(Method method) {

        KClass<?> kotlinClass = JvmClassMappingKt.getKotlinClass(method.getDeclaringClass());

        return kotlinClass.getMembers() //
                .stream() //
                .flatMap(KotlinReflectionUtils::toKFunctionStream) //
                .filter(it -> isSame(it, method)) //
                .findFirst();
    }

    private static Stream<? extends KFunction<?>> toKFunctionStream(KCallable<?> it) {

        if (it instanceof KMutableProperty<?>) {

            KMutableProperty<?> property = (KMutableProperty<?>) it;
            return Stream.of(property.getGetter(), property.getSetter());
        }

        if (it instanceof KProperty<?>) {

            KProperty<?> property = (KProperty<?>) it;
            return Stream.of(property.getGetter());
        }

        if (it instanceof KFunction<?>) {
            return Stream.of((KFunction<?>) it);
        }

        return Stream.empty();
    }

    private static boolean isSame(KFunction<?> function, Method method) {

        Method javaMethod = ReflectJvmMapping.getJavaMethod(function);
        return javaMethod != null && javaMethod.equals(method);
    }

    private enum KotlinClassHeaderKind {

        CLASS(1), FILE(2), SYNTHETIC_CLASS(3), MULTI_FILE_CLASS_FACADE(4), MULTI_FILE_CLASS_PART(5);

        int id;

        KotlinClassHeaderKind(int val) {
            this.id = val;
        }
    }
}
