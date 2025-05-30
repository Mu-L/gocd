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
package com.thoughtworks.go.util.comparator;

import java.util.*;
import java.util.stream.Collectors;

public class AlphaAsciiCollectionComparator<T> implements Comparator<Collection<? extends Comparable<T>>> {
    private final AlphaAsciiComparator comparator;

    public AlphaAsciiCollectionComparator() {
        comparator = new AlphaAsciiComparator();
    }

    @Override
    public int compare(Collection<? extends Comparable<T>> one, Collection<? extends Comparable<T>> other) {
        return comparator.compare(string(one), string(other));
    }

    private String string(Collection<? extends Comparable<T>> other) {
        List<Comparable<?>> others = new ArrayList<>(other);
        others.sort(null);
        return others.stream().map(obj -> Objects.toString(obj, "")).collect(Collectors.joining());
    }
}
