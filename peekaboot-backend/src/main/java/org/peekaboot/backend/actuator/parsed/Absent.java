package org.peekaboot.backend.actuator.parsed;

import java.util.List;
import java.util.Map;

/** Jackson binds a collection the response left out as null; the records replace that with the empty one. */
final class Absent {

    private Absent() {}

    static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }

    static <K, V> Map<K, V> orEmpty(Map<K, V> map) {
        return map != null ? map : Map.of();
    }
}
