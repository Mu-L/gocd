/*
 * Copyright Thoughtworks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thoughtworks.go.server.cache;

import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class LazyCacheTest {

    @Test
    public void shouldGetValueFromCacheIfPresent() {
        Object valueInCache = new Object();

        Ehcache ehCache = mock(Ehcache.class);
        when(ehCache.get("foo")).thenReturn(new Element("foo", valueInCache));

        Supplier<?> supplier = mock(Supplier.class);
        assertThat(new LazyCache(ehCache, null).get("foo", supplier)).isSameAs(valueInCache);
        verifyNoInteractions(supplier);
    }

    @Test
    public void shouldComputeValueFromSupplierIfNotPresentInCache() {
        Ehcache ehCache = mock(Ehcache.class);
        when(ehCache.get("foo")).thenReturn(null);

        Object lazilyComputedValue = new Object();

        @SuppressWarnings("unchecked") Supplier<Object> supplier = mock(Supplier.class);
        when(supplier.get()).thenReturn(lazilyComputedValue);
        assertThat(new LazyCache(ehCache, null).get("foo", supplier)).isSameAs(lazilyComputedValue);
        verify(supplier, times(1)).get();

        verify(ehCache, times(2)).get("foo");
        verify(ehCache, times(1)).put(new Element("foo", lazilyComputedValue));
    }
}
