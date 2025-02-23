/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.provider;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.internal.provider.MapCollectors.EntriesFromMap;
import org.gradle.api.internal.provider.MapCollectors.EntriesFromMapProvider;
import org.gradle.api.internal.provider.MapCollectors.EntryWithValueFromProvider;
import org.gradle.api.internal.provider.MapCollectors.SingleEntry;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.MapPropertyConfigurer;
import org.gradle.api.provider.Provider;
import org.gradle.util.internal.ConfigureUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.gradle.internal.Cast.uncheckedCast;
import static org.gradle.internal.Cast.uncheckedNonnullCast;

public class DefaultMapProperty<K, V> extends AbstractProperty<Map<K, V>, DefaultMapProperty.MapSupplierGuard<K, V>> implements MapProperty<K, V>, MapProviderInternal<K, V> {
    private static final String NULL_KEY_FORBIDDEN_MESSAGE = String.format("Cannot add an entry with a null key to a property of type %s.", Map.class.getSimpleName());
    private static final String NULL_VALUE_FORBIDDEN_MESSAGE = String.format("Cannot add an entry with a null value to a property of type %s.", Map.class.getSimpleName());

    private static final MapSupplier<Object, Object> NO_VALUE = new NoValueSupplier<>(Value.missing());

    private final Class<K> keyType;
    private final Class<V> valueType;
    private final ValueCollector<K> keyCollector;
    private final MapEntryCollector<K, V> entryCollector;
    private MapSupplierGuard<K, V> defaultValue = emptySupplier();

    public DefaultMapProperty(PropertyHost propertyHost, Class<K> keyType, Class<V> valueType) {
        super(propertyHost);
        this.keyType = keyType;
        this.valueType = valueType;
        keyCollector = new ValidatingValueCollector<>(Set.class, keyType, ValueSanitizers.forType(keyType));
        entryCollector = new ValidatingMapEntryCollector<>(keyType, valueType, ValueSanitizers.forType(keyType), ValueSanitizers.forType(valueType));
        init(defaultValue, noValueSupplier());
    }

    @Override
    protected MapSupplierGuard<K, V> getDefaultConvention() {
        return noValueSupplier();
    }

    @Override
    protected boolean isDefaultConvention() {
        return isNoValueSupplier(getConventionSupplier());
    }

    private MapSupplierGuard<K, V> emptySupplier() {
        return guard(new EmptySupplier());
    }

    private MapSupplierGuard<K, V> noValueSupplier() {
        return guard(uncheckedCast(NO_VALUE));
    }

    private void setConvention(MapSupplier<K, V> unguardedConvention) {
        setConvention(guard(unguardedConvention));
    }

    private void setSupplier(MapSupplier<K, V> unguardedSupplier) {
        setSupplier(guard(unguardedSupplier));
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public Class<Map<K, V>> getType() {
        return (Class) Map.class;
    }

    @Override
    public Class<K> getKeyType() {
        return keyType;
    }

    @Override
    public Class<V> getValueType() {
        return valueType;
    }

    @Override
    public Class<?> publicType() {
        return MapProperty.class;
    }

    @Override
    public int getFactoryId() {
        return ManagedFactories.MapPropertyManagedFactory.FACTORY_ID;
    }

    @Override
    public Provider<V> getting(final K key) {
        return new EntryProvider(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public MapProperty<K, V> empty() {
        setSupplier(emptySupplier());
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setFromAnyValue(@Nullable Object object) {
        if (object == null || object instanceof Map<?, ?>) {
            set((Map) object);
        } else if (object instanceof Provider<?>) {
            set((Provider) object);
        } else {
            throw new IllegalArgumentException(String.format(
                "Cannot set the value of a property of type %s using an instance of type %s.", Map.class.getName(), object.getClass().getName()));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void set(@Nullable Map<? extends K, ? extends V> entries) {
        if (entries == null) {
            discardValue();
            defaultValue = noValueSupplier();
        } else {
            setSupplier(new CollectingSupplier(new MapCollectors.EntriesFromMap<>(entries)));
        }
    }

    @Override
    public void set(Provider<? extends Map<? extends K, ? extends V>> provider) {
        ProviderInternal<? extends Map<? extends K, ? extends V>> p = checkMapProvider(provider);
        setSupplier(new CollectingSupplier(new MapCollectors.EntriesFromMapProvider<>(p)));
    }

    @Override
    public MapProperty<K, V> value(@Nullable Map<? extends K, ? extends V> entries) {
        set(entries);
        return this;
    }

    @Override
    public MapProperty<K, V> value(Provider<? extends Map<? extends K, ? extends V>> provider) {
        set(provider);
        return this;
    }

    @Override
    public void put(K key, V value) {
        getExplicitValue().put(key, value);
    }

    @Override
    public void put(K key, Provider<? extends V> providerOfValue) {
        getExplicitValue().put(key, providerOfValue);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> entries) {
        getExplicitValue().putAll(entries);
    }

    @Override
    public void putAll(Provider<? extends Map<? extends K, ? extends V>> provider) {
        getExplicitValue().putAll(provider);
    }

    private void addExplicitCollector(MapCollector<K, V> collector) {
        assertCanMutate();
        setSupplier(getExplicitValue(defaultValue).plus(collector));
    }

    private MapPropertyConfigurer<K, V> getExplicitValue() {
        return new ExplicitConfigurer();
    }

    @Override
    public MapProperty<K, V> withActualValue(Action<MapPropertyConfigurer<K, V>> action) {
        setToConventionIfUnset();
        action.execute(getExplicitValue());
        return this;
    }

    @Override
    public MapProperty<K, V> withActualValue(@DelegatesTo(MapPropertyConfigurer.class) Closure<Void> action) {
        setToConventionIfUnset();
        ConfigureUtil.configure(action, getExplicitValue());
        return this;
    }

    private boolean isNoValueSupplier(MapSupplierGuard<K, V> valueSupplier) {
        return valueSupplier.supplier instanceof NoValueSupplier;
    }

    private ProviderInternal<? extends Map<? extends K, ? extends V>> checkMapProvider(@Nullable Provider<? extends Map<? extends K, ? extends V>> provider) {
        return checkMapProvider("value", provider);
    }

    @SuppressWarnings("unchecked")
    private ProviderInternal<? extends Map<? extends K, ? extends V>> checkMapProvider(String valueKind, @Nullable Provider<? extends Map<? extends K, ? extends V>> provider) {
        if (provider == null) {
            throw new IllegalArgumentException(String.format("Cannot set the %s of a property using a null provider.", valueKind));
        }
        ProviderInternal<? extends Map<? extends K, ? extends V>> p = Providers.internal(provider);
        if (p.getType() != null && !Map.class.isAssignableFrom(p.getType())) {
            throw new IllegalArgumentException(String.format("Cannot set the %s of a property of type %s using a provider of type %s.",
                valueKind,
                Map.class.getName(), p.getType().getName()));
        }
        if (p instanceof MapProviderInternal) {
            Class<? extends K> providerKeyType = ((MapProviderInternal<? extends K, ? extends V>) p).getKeyType();
            Class<? extends V> providerValueType = ((MapProviderInternal<? extends K, ? extends V>) p).getValueType();
            if (!keyType.isAssignableFrom(providerKeyType) || !valueType.isAssignableFrom(providerValueType)) {
                throw new IllegalArgumentException(String.format("Cannot set the %s of a property of type %s with key type %s and value type %s " +
                        "using a provider with key type %s and value type %s.", valueKind, Map.class.getName(), keyType.getName(), valueType.getName(),
                    providerKeyType.getName(), providerValueType.getName()));
            }
        }
        return p;
    }

    @Override
    public MapProperty<K, V> convention(@Nullable Map<? extends K, ? extends V> value) {
        if (value == null) {
            setConvention(noValueSupplier());
        } else {
            setConvention(new CollectingSupplier(new EntriesFromMap<>(value)));
        }
        return this;
    }

    @Override
    public MapProperty<K, V> convention(Provider<? extends Map<? extends K, ? extends V>> valueProvider) {
        setConvention(new CollectingSupplier(new EntriesFromMapProvider<>(Providers.internal(valueProvider))));
        return this;
    }

    @Override
    public MapProperty<K, V> unsetConvention() {
        discardConvention();
        return this;
    }

    @Override
    public MapProperty<K, V> unset() {
        discardValue();
        return this;
    }

    public void fromState(ExecutionTimeValue<? extends Map<? extends K, ? extends V>> value) {
        if (value.isMissing()) {
            setSupplier(noValueSupplier());
        } else if (value.hasFixedValue()) {
            setSupplier(new FixedSupplier<>(uncheckedNonnullCast(value.getFixedValue()), uncheckedCast(value.getSideEffect())));
        } else {
            setSupplier(new CollectingSupplier(new EntriesFromMapProvider<>(value.getChangingValue())));
        }
    }

    @Override
    public Provider<Set<K>> keySet() {
        return new KeySetProvider();
    }

    @Override
    public void update(Transformer<? extends @org.jetbrains.annotations.Nullable Provider<? extends Map<? extends K, ? extends V>>, ? super Provider<Map<K, V>>> transform) {
        Provider<? extends Map<? extends K, ? extends V>> newValue = transform.transform(shallowCopy());
        if (newValue != null) {
            set(newValue);
        } else {
            set((Map<? extends K, ? extends V>) null);
        }
    }

    @Override
    protected String describeContents() {
        return String.format("Map(%s->%s, %s)", keyType.getSimpleName().toLowerCase(), valueType.getSimpleName(), getSupplier());
    }

    @Override
    protected Value<? extends Map<K, V>> calculateValueFrom(MapSupplierGuard<K, V> value, ValueConsumer consumer) {
        return value.calculateValue(consumer);
    }

    @Override
    protected MapSupplierGuard<K, V> finalValue(MapSupplierGuard<K, V> value, ValueConsumer consumer) {
        Value<? extends Map<K, V>> result = value.calculateValue(consumer);
        if (!result.isMissing()) {
            return guard(new FixedSupplier<>(result.getWithoutSideEffect(), uncheckedCast(result.getSideEffect())));
        } else if (result.getPathToOrigin().isEmpty()) {
            return noValueSupplier();
        } else {
            return guard(new NoValueSupplier<>(result));
        }
    }

    @Override
    protected ExecutionTimeValue<? extends Map<K, V>> calculateOwnExecutionTimeValue(MapSupplierGuard<K, V> value) {
        return value.calculateOwnExecutionTimeValue();
    }

    private class EntryProvider extends AbstractMinimalProvider<V> {
        private final K key;

        public EntryProvider(K key) {
            this.key = key;
        }

        @Nullable
        @Override
        public Class<V> getType() {
            return valueType;
        }

        @Override
        protected Value<? extends V> calculateOwnValue(ValueConsumer consumer) {
            Value<? extends Map<K, V>> result = DefaultMapProperty.this.calculateOwnValue(consumer);
            if (result.isMissing()) {
                return result.asType();
            }
            Value<? extends V> resultValue = Value.ofNullable(result.getWithoutSideEffect().get(key));
            return resultValue.withSideEffect(SideEffect.fixedFrom(result));
        }
    }

    private class KeySetProvider extends AbstractMinimalProvider<Set<K>> {
        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public Class<Set<K>> getType() {
            return (Class) Set.class;
        }

        @Override
        protected Value<? extends Set<K>> calculateOwnValue(ValueConsumer consumer) {
            beforeRead(consumer);
            return getSupplier().calculateKeys(consumer);
        }
    }

    private static class NoValueSupplier<K, V> implements MapSupplier<K, V> {
        private final Value<? extends Map<K, V>> value;

        public NoValueSupplier(Value<? extends Map<K, V>> value) {
            this.value = value.asType();
            assert value.isMissing();
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return false;
        }

        @Override
        public Value<? extends Map<K, V>> calculateValue(ValueConsumer consumer) {
            return value;
        }

        @Override
        public Value<? extends Set<K>> calculateKeys(ValueConsumer consumer) {
            return value.asType();
        }

        @Override
        public MapSupplier<K, V> plus(MapCollector<K, V> collector) {
            // nothing + something = nothing
            return this;
        }

        @Override
        public ExecutionTimeValue<? extends Map<K, V>> calculateOwnExecutionTimeValue() {
            return ExecutionTimeValue.missing();
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.unknown();
        }
    }

    private class EmptySupplier implements MapSupplier<K, V> {
        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return true;
        }

        @Override
        public Value<? extends Map<K, V>> calculateValue(ValueConsumer consumer) {
            return Value.of(ImmutableMap.of());
        }

        @Override
        public Value<? extends Set<K>> calculateKeys(ValueConsumer consumer) {
            return Value.of(ImmutableSet.of());
        }

        @Override
        public MapSupplier<K, V> plus(MapCollector<K, V> collector) {
            // empty + something = something
            return new CollectingSupplier(collector);
        }

        @Override
        public ExecutionTimeValue<? extends Map<K, V>> calculateOwnExecutionTimeValue() {
            return ExecutionTimeValue.fixedValue(ImmutableMap.of());
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.noProducer();
        }
    }

    private static class FixedSupplier<K, V> implements MapSupplier<K, V> {
        private final Map<K, V> entries;
        private final SideEffect<? super Map<K, V>> sideEffect;

        public FixedSupplier(Map<K, V> entries, @Nullable SideEffect<? super Map<K, V>> sideEffect) {
            this.entries = entries;
            this.sideEffect = sideEffect;
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return true;
        }

        @Override
        public Value<? extends Map<K, V>> calculateValue(ValueConsumer consumer) {
            return Value.of(entries).withSideEffect(sideEffect);
        }

        @Override
        public Value<? extends Set<K>> calculateKeys(ValueConsumer consumer) {
            return Value.of(entries.keySet());
        }

        @Override
        public MapSupplier<K, V> plus(MapCollector<K, V> collector) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecutionTimeValue<? extends Map<K, V>> calculateOwnExecutionTimeValue() {
            return ExecutionTimeValue.fixedValue(entries).withSideEffect(sideEffect);
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.unknown();
        }
    }

    private class CollectingSupplier implements MapSupplier<K, V> {
        private final MapCollector<K, V> collector;

        public CollectingSupplier(MapCollector<K, V> collector) {
            this.collector = collector;
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return collector.calculatePresence(consumer);
        }

        @Override
        public Value<? extends Set<K>> calculateKeys(ValueConsumer consumer) {
            // TODO - don't make a copy when the collector already produces an immutable collection
            ImmutableSet.Builder<K> builder = ImmutableSet.builder();
            Value<Void> result = collector.collectKeys(consumer, keyCollector, builder);
            if (result.isMissing()) {
                return result.asType();
            }
            return Value.of(ImmutableSet.copyOf(builder.build())).withSideEffect(SideEffect.fixedFrom(result));
        }

        @Override
        public Value<? extends Map<K, V>> calculateValue(ValueConsumer consumer) {
            // TODO - don't make a copy when the collector already produces an immutable collection
            // Cannot use ImmutableMap.Builder here, as it does not allow multiple entries with the same key, however the contract
            // for MapProperty allows a provider to override the entries of earlier providers and so there can be multiple entries
            // with the same key
            Map<K, V> entries = new LinkedHashMap<>();
            Value<Void> result = collector.collectEntries(consumer, entryCollector, entries);
            if (result.isMissing()) {
                return result.asType();
            }
            return Value.of(ImmutableMap.copyOf(entries)).withSideEffect(SideEffect.fixedFrom(result));
        }

        @Override
        public MapSupplier<K, V> plus(MapCollector<K, V> collector) {
            return new CollectingSupplier(new PlusCollector<>(this.collector, collector));
        }

        @Override
        public ExecutionTimeValue<? extends Map<K, V>> calculateOwnExecutionTimeValue() {
            List<ExecutionTimeValue<? extends Map<? extends K, ? extends V>>> execTimeValues = collectExecutionTimeValues();
            ExecutionTimeValue<Map<K, V>> fixedOrMissing = fixedOrMissingValueOf(execTimeValues);
            return fixedOrMissing != null
                ? fixedOrMissing
                : ExecutionTimeValue.changingValue(new CollectingProvider<>(execTimeValues));
        }

        /**
         * Try to simplify the set of execution values to either a missing value or a fixed value.
         */
        @Nullable
        private ExecutionTimeValue<Map<K, V>> fixedOrMissingValueOf(List<ExecutionTimeValue<? extends Map<? extends K, ? extends V>>> values) {
            boolean fixed = true;
            boolean changingContent = false;
            for (ExecutionTimeValue<? extends Map<? extends K, ? extends V>> value : values) {
                if (value.isMissing()) {
                    return ExecutionTimeValue.missing();
                }
                if (value.isChangingValue()) {
                    fixed = false;
                } else if (value.hasChangingContent()) {
                    changingContent = true;
                }
            }
            if (fixed) {
                SideEffectBuilder<? super Map<K, V>> sideEffectBuilder = SideEffect.builder();
                ImmutableMap<K, V> entries = collectEntries(values, sideEffectBuilder);
                return maybeChangingContent(ExecutionTimeValue.fixedValue(entries), changingContent)
                    .withSideEffect(sideEffectBuilder.build());
            }
            return null;
        }

        private List<ExecutionTimeValue<? extends Map<? extends K, ? extends V>>> collectExecutionTimeValues() {
            List<ExecutionTimeValue<? extends Map<? extends K, ? extends V>>> values = new ArrayList<>();
            collector.calculateExecutionTimeValue(values::add);
            return values;
        }

        private ImmutableMap<K, V> collectEntries(List<ExecutionTimeValue<? extends Map<? extends K, ? extends V>>> values, SideEffectBuilder<? super Map<K, V>> sideEffectBuilder) {
            Map<K, V> entries = new LinkedHashMap<>();
            for (ExecutionTimeValue<? extends Map<? extends K, ? extends V>> value : values) {
                entryCollector.addAll(value.getFixedValue().entrySet(), entries);
                sideEffectBuilder.add(SideEffect.fixedFrom(value));
            }
            return ImmutableMap.copyOf(entries);
        }

        private ExecutionTimeValue<Map<K, V>> maybeChangingContent(ExecutionTimeValue<Map<K, V>> value, boolean changingContent) {
            return changingContent ? value.withChangingContent() : value;
        }

        @Override
        public ValueProducer getProducer() {
            return collector.getProducer();
        }
    }

    private static class CollectingProvider<K, V> extends AbstractMinimalProvider<Map<K, V>> {
        private final List<ExecutionTimeValue<? extends Map<? extends K, ? extends V>>> values;

        public CollectingProvider(List<ExecutionTimeValue<? extends Map<? extends K, ? extends V>>> values) {
            this.values = values;
        }

        @Nullable
        @Override
        public Class<Map<K, V>> getType() {
            return uncheckedCast(Map.class);
        }

        @Override
        public ExecutionTimeValue<? extends Map<K, V>> calculateExecutionTimeValue() {
            return ExecutionTimeValue.changingValue(this);
        }

        @Override
        protected Value<? extends Map<K, V>> calculateOwnValue(ValueConsumer consumer) {
            Map<K, V> entries = new LinkedHashMap<>();
            SideEffectBuilder<? super Map<K, V>> sideEffectBuilder = SideEffect.builder();
            for (ExecutionTimeValue<? extends Map<? extends K, ? extends V>> executionTimeValue : values) {
                Value<? extends Map<? extends K, ? extends V>> value = executionTimeValue.toProvider().calculateValue(consumer);
                if (value.isMissing()) {
                    return Value.missing();
                }
                entries.putAll(value.getWithoutSideEffect());
                sideEffectBuilder.add(SideEffect.fixedFrom(value));
            }

            return Value.of(ImmutableMap.copyOf(entries)).withSideEffect(sideEffectBuilder.build());
        }
    }

    private abstract class Configurer implements MapPropertyConfigurer<K, V> {
        abstract void addCollector(MapCollector<K, V> collector);

        @Override
        public void put(K key, V value) {
            Preconditions.checkNotNull(key, NULL_KEY_FORBIDDEN_MESSAGE);
            Preconditions.checkNotNull(value, NULL_VALUE_FORBIDDEN_MESSAGE);
            addCollector(new SingleEntry<>(key, value));
        }

        @Override
        public void put(K key, Provider<? extends V> providerOfValue) {
            Preconditions.checkNotNull(key, NULL_KEY_FORBIDDEN_MESSAGE);
            Preconditions.checkNotNull(providerOfValue, NULL_VALUE_FORBIDDEN_MESSAGE);
            ProviderInternal<? extends V> p = Providers.internal(providerOfValue);
            if (p.getType() != null && !valueType.isAssignableFrom(p.getType())) {
                throw new IllegalArgumentException(String.format("Cannot add an entry to a property of type %s with values of type %s using a provider of type %s.",
                    Map.class.getName(), valueType.getName(), p.getType().getName()));
            }
            addCollector(new EntryWithValueFromProvider<>(key, Providers.internal(providerOfValue)));
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> entries) {
            addCollector(new EntriesFromMap<>(entries));
        }

        @Override
        public void putAll(Provider<? extends Map<? extends K, ? extends V>> provider) {
            ProviderInternal<? extends Map<? extends K, ? extends V>> p = checkMapProvider(provider);
            addCollector(new EntriesFromMapProvider<>(Providers.internal(provider)));
        }
    }

    private class ExplicitConfigurer extends Configurer {
        @Override
        void addCollector(MapCollector<K, V> collector) {
            addExplicitCollector(collector);
        }
    }

    private static class PlusCollector<K, V> implements MapCollector<K, V> {
        private final MapCollector<K, V> left;
        private final MapCollector<K, V> right;

        public PlusCollector(MapCollector<K, V> left, MapCollector<K, V> right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return left.calculatePresence(consumer) && right.calculatePresence(consumer);
        }

        @Override
        public Value<Void> collectEntries(ValueConsumer consumer, MapEntryCollector<K, V> collector, Map<K, V> dest) {
            Value<Void> leftValue = left.collectEntries(consumer, collector, dest);
            if (leftValue.isMissing()) {
                return leftValue;
            }
            Value<Void> rightValue = right.collectEntries(consumer, collector, dest);
            if (rightValue.isMissing()) {
                return rightValue;
            }

            return Value.present()
                .withSideEffect(SideEffect.fixedFrom(leftValue))
                .withSideEffect(SideEffect.fixedFrom(rightValue));
        }

        @Override
        public Value<Void> collectKeys(ValueConsumer consumer, ValueCollector<K> collector, ImmutableCollection.Builder<K> dest) {
            Value<Void> result = left.collectKeys(consumer, collector, dest);
            if (result.isMissing()) {
                return result;
            }
            return right.collectKeys(consumer, collector, dest);
        }

        @Override
        public void calculateExecutionTimeValue(Action<ExecutionTimeValue<? extends Map<? extends K, ? extends V>>> visitor) {
            left.calculateExecutionTimeValue(visitor);
            right.calculateExecutionTimeValue(visitor);
        }

        @Override
        public ValueProducer getProducer() {
            return left.getProducer().plus(right.getProducer());
        }
    }

    private MapSupplierGuard<K, V> guard(MapSupplier<K, V> supplier) {
        return new MapSupplierGuard<>(this, supplier);
    }

    protected static final class MapSupplierGuard<K, V> implements MapSupplier<K, V>, GuardedData<MapSupplier<K, V>>, GuardedValueSupplier<MapSupplierGuard<K, V>> {
        private final EvaluationContext.EvaluationOwner owner;
        private final MapSupplier<K, V> supplier;

        public MapSupplierGuard(EvaluationContext.EvaluationOwner owner, MapSupplier<K, V> supplier) {
            this.owner = owner;
            this.supplier = supplier;
        }

        @Override
        public MapSupplierGuard<K, V> withOwner(EvaluationContext.EvaluationOwner newOwner) {
            return new MapSupplierGuard<>(newOwner, supplier);
        }

        @Override
        public EvaluationContext.EvaluationOwner getOwner() {
            return owner;
        }

        @Override
        public MapSupplier<K, V> unsafeGet() {
            return supplier;
        }

        @Override
        public Value<? extends Map<K, V>> calculateValue(ValueConsumer consumer) {
            try (EvaluationContext.ScopeContext ignore = EvaluationContext.current().open(owner)) {
                return supplier.calculateValue(consumer);
            }
        }

        @Override
        public Value<? extends Set<K>> calculateKeys(ValueConsumer consumer) {
            try (EvaluationContext.ScopeContext ignore = EvaluationContext.current().open(owner)) {
                return supplier.calculateKeys(consumer);
            }
        }

        @Override
        public MapSupplierGuard<K, V> plus(MapCollector<K, V> collector) {
            return new MapSupplierGuard<>(owner, supplier.plus(collector));
        }

        @Override
        public ExecutionTimeValue<? extends Map<K, V>> calculateOwnExecutionTimeValue() {
            try (EvaluationContext.ScopeContext ignore = EvaluationContext.current().open(owner)) {
                return supplier.calculateOwnExecutionTimeValue();
            }
        }

        @Override
        public ValueProducer getProducer() {
            try (EvaluationContext.ScopeContext ignore = EvaluationContext.current().open(owner)) {
                return supplier.getProducer();
            }
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            try (EvaluationContext.ScopeContext ignore = EvaluationContext.current().open(owner)) {
                return supplier.calculatePresence(consumer);
            }
        }
    }
}
