package com.redis.riot.core;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.asm.MethodVisitor;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.CodeFlow;
import org.springframework.expression.spel.CompilablePropertyAccessor;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;

public class ReflectivePropertyAccessor implements PropertyAccessor {

    static {
        Set<Class<?>> booleanTypes = new HashSet<>(4);
        booleanTypes.add(Boolean.class);
        booleanTypes.add(Boolean.TYPE);
    }

    private final boolean allowWrite;
    private final Map<PropertyCacheKey, InvokerPair> readerCache = new ConcurrentHashMap<>(64);
    private final Map<PropertyCacheKey, Member> writerCache = new ConcurrentHashMap<>(64);
    private final Map<PropertyCacheKey, TypeDescriptor> typeDescriptorCache = new ConcurrentHashMap<>(64);
    @Nullable
    private volatile InvokerPair lastReadInvokerPair;

    /**
     * Create a new property accessor for reading as well writing.
     *
     * @see #ReflectivePropertyAccessor(boolean)
     */
    public ReflectivePropertyAccessor() {
        this.allowWrite = true;
    }

    /**
     * Create a new property accessor for reading and possibly writing.
     *
     * @param allowWrite whether to also allow for write operations
     * @see #canWrite
     * @since 4.3.15
     */
    public ReflectivePropertyAccessor(boolean allowWrite) {
        this.allowWrite = allowWrite;
    }

    /**
     * Returns {@code null} which means this is a general purpose accessor.
     */
    @Override
    @Nullable
    public Class<?>[] getSpecificTargetClasses() {
        return null;
    }

    @Override
    public boolean canRead(EvaluationContext context, @Nullable Object target, String name) throws AccessException {
        if (target == null) {
            return false;
        }

        Class<?> type = (target instanceof Class ? (Class<?>) target : target.getClass());
        if (type.isArray() && name.equals("length")) {
            return true;
        }

        PropertyCacheKey cacheKey = new PropertyCacheKey(type, name, target instanceof Class);
        if (this.readerCache.containsKey(cacheKey)) {
            return true;
        }
        Method method = getMethod(name, type);
        if (method != null) {
            TypeDescriptor typeDescriptor = new TypeDescriptor(new MethodParameter(method, -1));
            this.readerCache.put(cacheKey, new InvokerPair(method, typeDescriptor));
            this.typeDescriptorCache.put(cacheKey, typeDescriptor);
            return true;
        } else {
            Field field = findField(name, type, target);
            if (field != null) {
                TypeDescriptor typeDescriptor = new TypeDescriptor(field);
                this.readerCache.put(cacheKey, new InvokerPair(field, typeDescriptor));
                this.typeDescriptorCache.put(cacheKey, typeDescriptor);
                return true;
            }
        }

        return false;
    }

    private Method getMethod(String name, Class<?> type) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    @Override
    public TypedValue read(EvaluationContext context, @Nullable Object target, String name) throws AccessException {
        Assert.state(target != null, "Target must not be null");
        Class<?> type = (target instanceof Class ? (Class<?>) target : target.getClass());

        if (type.isArray() && name.equals("length")) {
            if (target instanceof Class) {
                throw new AccessException("Cannot access length on array class itself");
            }
            return new TypedValue(Array.getLength(target));
        }

        PropertyCacheKey cacheKey = new PropertyCacheKey(type, name, target instanceof Class);
        InvokerPair invoker = this.readerCache.get(cacheKey);
        this.lastReadInvokerPair = invoker;

        if (invoker == null || invoker.member instanceof Method) {
            Method method = (Method) (invoker != null ? invoker.member : null);
            if (method == null) {
                method = getMethod(name, type);
                if (method != null) {
                    // Treat it like a property...
                    // The readerCache will only contain gettable properties (let's not worry about
                    // setters for now).
                    TypeDescriptor typeDescriptor = new TypeDescriptor(new MethodParameter(method, -1));
                    invoker = new InvokerPair(method, typeDescriptor);
                    this.lastReadInvokerPair = invoker;
                    this.readerCache.put(cacheKey, invoker);
                }
            }
            if (method != null) {
                try {
                    ReflectionUtils.makeAccessible(method);
                    Object value = method.invoke(target);
                    return new TypedValue(value, invoker.typeDescriptor.narrow(value));
                } catch (Exception ex) {
                    throw new AccessException("Unable to access property '" + name + "' through getter method", ex);
                }
            }
        }

        if (invoker == null || invoker.member instanceof Field) {
            Field field = (Field) (invoker == null ? null : invoker.member);
            if (field == null) {
                field = findField(name, type, target);
                if (field != null) {
                    invoker = new InvokerPair(field, new TypeDescriptor(field));
                    this.lastReadInvokerPair = invoker;
                    this.readerCache.put(cacheKey, invoker);
                }
            }
            if (field != null) {
                try {
                    ReflectionUtils.makeAccessible(field);
                    Object value = field.get(target);
                    return new TypedValue(value, invoker.typeDescriptor.narrow(value));
                } catch (Exception ex) {
                    throw new AccessException("Unable to access field '" + name + "'", ex);
                }
            }
        }

        throw new AccessException("Neither getter method nor field found for property '" + name + "'");
    }

    @Override
    public boolean canWrite(EvaluationContext context, @Nullable Object target, String name) throws AccessException {
        if (!this.allowWrite || target == null) {
            return false;
        }

        Class<?> type = (target instanceof Class ? (Class<?>) target : target.getClass());
        PropertyCacheKey cacheKey = new PropertyCacheKey(type, name, target instanceof Class);
        if (this.writerCache.containsKey(cacheKey)) {
            return true;
        }

        Method method = getMethod(name, type);
        if (method != null) {
            TypeDescriptor typeDescriptor = new TypeDescriptor(new MethodParameter(method, -1));
            this.writerCache.put(cacheKey, method);
            this.typeDescriptorCache.put(cacheKey, typeDescriptor);
            return true;
        } else {
            Field field = findField(name, type, target);
            if (field != null) {
                this.writerCache.put(cacheKey, field);
                this.typeDescriptorCache.put(cacheKey, new TypeDescriptor(field));
                return true;
            }
        }

        return false;
    }

    @Override
    public void write(EvaluationContext context, @Nullable Object target, String name, @Nullable Object newValue) throws AccessException {

        if (!this.allowWrite) {
            throw new AccessException("PropertyAccessor for property '" + name + "' on target [" + target + "] does not allow write operations");
        }

        Assert.state(target != null, "Target must not be null");
        Class<?> type = (target instanceof Class ? (Class<?>) target : target.getClass());

        Object possiblyConvertedNewValue = newValue;
        TypeDescriptor typeDescriptor = getTypeDescriptor(context, target, name);
        if (typeDescriptor != null) {
            try {
                possiblyConvertedNewValue = context.getTypeConverter().convertValue(newValue, TypeDescriptor.forObject(newValue), typeDescriptor);
            } catch (EvaluationException evaluationException) {
                throw new AccessException("Type conversion failure", evaluationException);
            }
        }

        PropertyCacheKey cacheKey = new PropertyCacheKey(type, name, target instanceof Class);
        Member cachedMember = this.writerCache.get(cacheKey);

        if (cachedMember == null || cachedMember instanceof Method) {
            Method method = (Method) cachedMember;
            if (method != null) {
                try {
                    ReflectionUtils.makeAccessible(method);
                    method.invoke(target, possiblyConvertedNewValue);
                    return;
                } catch (Exception ex) {
                    throw new AccessException("Unable to access property '" + name + "' through setter method", ex);
                }
            }
        }

        if (cachedMember == null || cachedMember instanceof Field) {
            Field field = (Field) cachedMember;
            if (field == null) {
                field = findField(name, type, target);
                if (field != null) {
                    cachedMember = field;
                    this.writerCache.put(cacheKey, cachedMember);
                }
            }
            if (field != null) {
                try {
                    ReflectionUtils.makeAccessible(field);
                    field.set(target, possiblyConvertedNewValue);
                    return;
                } catch (Exception ex) {
                    throw new AccessException("Unable to access field '" + name + "'", ex);
                }
            }
        }

        throw new AccessException("Neither setter method nor field found for property '" + name + "'");
    }

    @Nullable
    private TypeDescriptor getTypeDescriptor(EvaluationContext context, Object target, String name) {
        Class<?> type = (target instanceof Class ? (Class<?>) target : target.getClass());

        if (type.isArray() && name.equals("length")) {
            return TypeDescriptor.valueOf(Integer.TYPE);
        }
        PropertyCacheKey cacheKey = new PropertyCacheKey(type, name, target instanceof Class);
        TypeDescriptor typeDescriptor = this.typeDescriptorCache.get(cacheKey);
        if (typeDescriptor == null) {
            // Attempt to populate the cache entry
            try {
                if (canRead(context, target, name) || canWrite(context, target, name)) {
                    typeDescriptor = this.typeDescriptorCache.get(cacheKey);
                }
            } catch (AccessException ex) {
                // Continue with null type descriptor
            }
        }
        return typeDescriptor;
    }

    /**
     * Determine whether the given {@code Method} is a candidate for property access
     * on an instance of the given target class.
     * <p>
     * The default implementation considers any method as a candidate, even for
     * non-user-declared properties on the {@link Object} base class.
     *
     * @param method      the Method to evaluate
     * @param targetClass the concrete target class that is being introspected
     * @since 4.3.15
     */
    protected boolean isCandidateForProperty(Method method, Class<?> targetClass) {
        return true;
    }

    @Nullable
    private Field findField(String name, Class<?> clazz, Object target) {
        Field field = findField(name, clazz, target instanceof Class);
        if (field == null && target instanceof Class) {
            field = findField(name, target.getClass(), false);
        }
        return field;
    }

    /**
     * Find a field of a certain name on a specified class.
     */
    @Nullable
    protected Field findField(String name, Class<?> clazz, boolean mustBeStatic) {
        Field[] fields = clazz.getFields();
        for (Field field : fields) {
            if (field.getName().equals(name) && (!mustBeStatic || Modifier.isStatic(field.getModifiers()))) {
                return field;
            }
        }
        // We'll search superclasses and implemented interfaces explicitly,
        // although it shouldn't be necessary - however, see SPR-10125.
        if (clazz.getSuperclass() != null) {
            Field field = findField(name, clazz.getSuperclass(), mustBeStatic);
            if (field != null) {
                return field;
            }
        }
        for (Class<?> implementedInterface : clazz.getInterfaces()) {
            Field field = findField(name, implementedInterface, mustBeStatic);
            if (field != null) {
                return field;
            }
        }
        return null;
    }

    /**
     * Attempt to create an optimized property accessor tailored for a property of a
     * particular name on a particular class. The general ReflectivePropertyAccessor
     * will always work but is not optimal due to the need to lookup which
     * reflective member (method/field) to use each time read() is called. This
     * method will just return the ReflectivePropertyAccessor instance if it is
     * unable to build a more optimal accessor.
     * <p>
     * Note: An optimal accessor is currently only usable for read attempts. Do not
     * call this method if you need a read-write accessor.
     *
     * @see OptimalPropertyAccessor
     */
    public PropertyAccessor createOptimalAccessor(@Nullable Object target, String name) {
        // Don't be clever for arrays or a null target...
        if (target == null) {
            return this;
        }
        Class<?> clazz = (target instanceof Class ? (Class<?>) target : target.getClass());
        if (clazz.isArray()) {
            return this;
        }

        PropertyCacheKey cacheKey = new PropertyCacheKey(clazz, name, target instanceof Class);
        InvokerPair invocationTarget = this.readerCache.get(cacheKey);

        if (invocationTarget == null || invocationTarget.member instanceof Method) {
            Method method = (Method) (invocationTarget != null ? invocationTarget.member : null);
            if (method == null) {
                method = getMethod(name, clazz);
                if (method != null) {
                    invocationTarget = new InvokerPair(method, new TypeDescriptor(new MethodParameter(method, -1)));
                    ReflectionUtils.makeAccessible(method);
                    this.readerCache.put(cacheKey, invocationTarget);
                }
            }
            if (method != null) {
                return new OptimalPropertyAccessor(invocationTarget);
            }
        }

        if (invocationTarget == null || invocationTarget.member instanceof Field) {
            Field field = (invocationTarget != null ? (Field) invocationTarget.member : null);
            if (field == null) {
                field = findField(name, clazz, target instanceof Class);
                if (field != null) {
                    invocationTarget = new InvokerPair(field, new TypeDescriptor(field));
                    ReflectionUtils.makeAccessible(field);
                    this.readerCache.put(cacheKey, invocationTarget);
                }
            }
            if (field != null) {
                return new OptimalPropertyAccessor(invocationTarget);
            }
        }

        return this;
    }

    /**
     * Captures the member (method/field) to call reflectively to access a property
     * value and the type descriptor for the value returned by the reflective call.
     */
    private static class InvokerPair {

        final Member member;

        final TypeDescriptor typeDescriptor;

        public InvokerPair(Member member, TypeDescriptor typeDescriptor) {
            this.member = member;
            this.typeDescriptor = typeDescriptor;
        }
    }

    private static final class PropertyCacheKey implements Comparable<PropertyCacheKey> {

        private final Class<?> clazz;
        private final String property;
        private final boolean targetIsClass;

        public PropertyCacheKey(Class<?> clazz, String name, boolean targetIsClass) {
            this.clazz = clazz;
            this.property = name;
            this.targetIsClass = targetIsClass;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PropertyCacheKey)) {
                return false;
            }
            PropertyCacheKey otherKey = (PropertyCacheKey) other;
            return (this.clazz == otherKey.clazz && this.property.equals(otherKey.property) && this.targetIsClass == otherKey.targetIsClass);
        }

        @Override
        public int hashCode() {
            return (this.clazz.hashCode() * 29 + this.property.hashCode());
        }

        @Override
        public String toString() {
            return "CacheKey [clazz=" + this.clazz.getName() + ", property=" + this.property + ", " + this.property + ", targetIsClass=" + this.targetIsClass + "]";
        }

        @Override
        public int compareTo(PropertyCacheKey other) {
            int result = this.clazz.getName().compareTo(other.clazz.getName());
            if (result == 0) {
                result = this.property.compareTo(other.property);
            }
            return result;
        }
    }

    /**
     * An optimized form of a PropertyAccessor that will use reflection but only
     * knows how to access a particular property on a particular class. This is
     * unlike the general ReflectivePropertyResolver which manages a cache of
     * methods/fields that may be invoked to access different properties on
     * different classes. This optimal accessor exists because looking up the
     * appropriate reflective object by class/name on each read is not cheap.
     */
    public static class OptimalPropertyAccessor implements CompilablePropertyAccessor {

        /**
         * The member being accessed.
         */
        public final Member member;

        private final TypeDescriptor typeDescriptor;

        OptimalPropertyAccessor(InvokerPair target) {
            this.member = target.member;
            this.typeDescriptor = target.typeDescriptor;
        }

        @Override
        @Nullable
        public Class<?>[] getSpecificTargetClasses() {
            throw new UnsupportedOperationException("Should not be called on an OptimalPropertyAccessor");
        }

        @Override
        public boolean canRead(EvaluationContext context, @Nullable Object target, String name) {
            if (target == null) {
                return false;
            }
            Class<?> type = (target instanceof Class ? (Class<?>) target : target.getClass());
            if (type.isArray()) {
                return false;
            }

            if (this.member instanceof Method) {
                Method method = (Method) this.member;
                return name.equals(method.getName());
            } else {
                Field field = (Field) this.member;
                return field.getName().equals(name);
            }
        }

        @Override
        public TypedValue read(EvaluationContext context, @Nullable Object target, String name) throws AccessException {
            if (this.member instanceof Method) {
                Method method = (Method) this.member;
                try {
                    ReflectionUtils.makeAccessible(method);
                    Object value = method.invoke(target);
                    return new TypedValue(value, this.typeDescriptor.narrow(value));
                } catch (Exception ex) {
                    throw new AccessException("Unable to access property '" + name + "' through getter method", ex);
                }
            } else {
                Field field = (Field) this.member;
                try {
                    ReflectionUtils.makeAccessible(field);
                    Object value = field.get(target);
                    return new TypedValue(value, this.typeDescriptor.narrow(value));
                } catch (Exception ex) {
                    throw new AccessException("Unable to access field '" + name + "'", ex);
                }
            }
        }

        @Override
        public boolean canWrite(EvaluationContext context, @Nullable Object target, String name) {
            throw new UnsupportedOperationException("Should not be called on an OptimalPropertyAccessor");
        }

        @Override
        public void write(EvaluationContext context, @Nullable Object target, String name, @Nullable Object newValue) {
            throw new UnsupportedOperationException("Should not be called on an OptimalPropertyAccessor");
        }

        @Override
        public boolean isCompilable() {
            return (Modifier.isPublic(this.member.getModifiers()) && Modifier.isPublic(this.member.getDeclaringClass().getModifiers()));
        }

        @Override
        public Class<?> getPropertyType() {
            if (this.member instanceof Method) {
                return ((Method) this.member).getReturnType();
            } else {
                return ((Field) this.member).getType();
            }
        }

        @Override
        public void generateCode(String propertyName, MethodVisitor mv, CodeFlow cf) {
            boolean isStatic = Modifier.isStatic(this.member.getModifiers());
            String descriptor = cf.lastDescriptor();
            String classDesc = this.member.getDeclaringClass().getName().replace('.', '/');

            if (!isStatic) {
                if (descriptor == null) {
                    cf.loadTarget(mv);
                }
                if (descriptor == null || !classDesc.equals(descriptor.substring(1))) {
                    mv.visitTypeInsn(CHECKCAST, classDesc);
                }
            } else {
                if (descriptor != null) {
                    // A static field/method call will not consume what is on the stack,
                    // it needs to be popped off.
                    mv.visitInsn(POP);
                }
            }

            if (this.member instanceof Method) {
                mv.visitMethodInsn((isStatic ? INVOKESTATIC : INVOKEVIRTUAL), classDesc, this.member.getName(), CodeFlow.createSignatureDescriptor((Method) this.member), false);
            } else {
                mv.visitFieldInsn((isStatic ? GETSTATIC : GETFIELD), classDesc, this.member.getName(), CodeFlow.toJvmDescriptor(((Field) this.member).getType()));
            }
        }
    }

}
