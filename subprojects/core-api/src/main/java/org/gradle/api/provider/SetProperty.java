/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.api.provider;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Transformer;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Represents a property whose type is a {@link Set} of elements of type {@link T}. Retains iteration order.
 *
 * <p>
 * You can create a {@link SetProperty} instance using factory method {@link org.gradle.api.model.ObjectFactory#setProperty(Class)}.
 * </p>
 *
 * <p><b>Note:</b> This interface is not intended for implementation by build script or plugin authors.
 *
 * @param <T> the type of elements.
 * @since 4.5
 */
public interface SetProperty<T> extends Provider<Set<T>>, HasMultipleValues<T> {
    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> empty();

    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> value(@Nullable Iterable<? extends T> elements);

    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> value(Provider<? extends Iterable<? extends T>> provider);

    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> convention(@Nullable Iterable<? extends T> elements);

    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> convention(Provider<? extends Iterable<? extends T>> provider);

    /**
     * {@inheritDoc}
     *
     * <p>
     * This is similar to calling {@link #value(Iterable)} with a <code>null</code> argument.
     * </p>
     */
    @Override
    SetProperty<T> unset();

    /**
     * {@inheritDoc}
     * <p>
     * This is similar to calling {@link #convention(Iterable)} with a <code>null</code> argument.
     * </p>
     */
    @Override
    SetProperty<T> unsetConvention();

    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> withActualValue(Action<CollectionPropertyConfigurer<T>> action);

    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> withActualValue(Closure<Void> action);

    /**
     * Applies an eager transformation to the current value of the property "in place", without explicitly obtaining it.
     * The provided transformer is applied to the provider of the current value, and the returned provider is used as a new value.
     * The provider of the value can be used to derive the new value, but doesn't have to.
     * Returning null from the transformer unsets the property.
     * For example, the current value of a string set property can be filtered to exclude vowels:
     * <pre class='autoTested'>
     *     def property = objects.setProperty(String).value(["a", "b"])
     *
     *     property.update { it.map { value -&gt; value - ["a", "e", "i", "o", "u"] } }
     *
     *     println(property.get()) // ["b"]
     * </pre>
     * <p>
     * Note that simply writing {@code property.set(property.map { ... } } doesn't work and will cause an exception because of a circular reference evaluation at runtime.
     * <p>
     * <b>Further changes to the value of the property, such as calls to {@link #set(Iterable)}, are not transformed, and override the update instead</b>.
     * Because of this, this method inherently depends on the order of property changes, and therefore must be used sparingly.
     * <p>
     * If the value of the property is specified via a provider, then the current value provider tracks that provider.
     * For example, changes to the upstream property are visible:
     * <pre class='autoTested'>
     *     def upstream = objects.setProperty(String).value(["a", "b"])
     *     def property = objects.setProperty(String).value(upstream)
     *
     *     property.update { it.map { value -&gt; value - ["a", "e", "i", "o", "u"] } }
     *     upstream.set(["e", "f"])
     *
     *     println(property.get()) // ["f"]
     * </pre>
     * The provided transformation runs <b>eagerly</b>, so it can capture any objects without introducing memory leaks and without breaking configuration caching.
     * However, transformations applied to the current value provider (like {@link Provider#map(Transformer)}) are subject to the usual constraints.
     * <p>
     * If the property has no explicit value set, then the current value comes from the convention.
     * Changes to convention of this property do not affect the current value provider in this case, though upstream changes are still visible if the convention was set to a provider.
     * If there is no convention too, then the current value is a provider without a value.
     * The updated value becomes the explicit value of the property.

     * @param transform the transformation to apply to the current value. May return null, which unsets the property.
     * @since 8.6
     */
    @Incubating
    void update(Transformer<? extends @org.jetbrains.annotations.Nullable Provider<? extends Iterable<? extends T>>, ? super Provider<Set<T>>> transform);
}
