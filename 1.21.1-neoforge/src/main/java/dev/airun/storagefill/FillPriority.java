package dev.airun.storagefill;

import dev.airun.storagefill.storage.StorageSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class FillPriority {
    public static final List<String> DEFAULT_NAMES = List.of("beyonddimensions", "refinedstorage", "ae2", "sophisticatedbackpacks");
    public static final FillPriority DEFAULT = new FillPriority(List.of(1, 2, 3, 4));
    private final List<Integer> ids;

    private FillPriority(List<Integer> ids) { this.ids = List.copyOf(ids); }

    public static boolean validName(Object value) { return value instanceof String name && DEFAULT_NAMES.contains(name); }

    public static FillPriority fromNames(List<? extends String> names) {
        var ids = new LinkedHashSet<Integer>();
        for (String name : names) {
            int index = DEFAULT_NAMES.indexOf(name);
            if (index >= 0) ids.add(index + 1);
        }
        ids.addAll(DEFAULT.ids);
        return new FillPriority(new ArrayList<>(ids));
    }

    public static FillPriority fromIds(List<Integer> ids) {
        if (ids.size() != 4 || new LinkedHashSet<>(ids).size() != 4
                || ids.stream().anyMatch(id -> id == null || id < 1 || id > 4)) {
            throw new IllegalArgumentException("Invalid fill source priority");
        }
        return new FillPriority(ids);
    }

    public List<Integer> ids() { return ids; }

    public int rank(int id) {
        int rank = ids.indexOf(id);
        return rank < 0 ? ids.size() : rank;
    }

    public List<StorageSource> ordered(List<StorageSource> sources) {
        var ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparingInt(source -> rank(source.sourceId())));
        return ordered;
    }

    public <K> List<Map<K, Integer>> tiers(Map<K, Integer> local, Map<Integer, Map<K, Integer>> remote) {
        var cumulative = new LinkedHashMap<>(local);
        List<Map<K, Integer>> tiers = new ArrayList<>(ids.size());
        tiers.add(new LinkedHashMap<>(cumulative));
        for (int i = 0; i < ids.size() - 1; i++) {
            for (var entry : remote.getOrDefault(ids.get(i), Map.of()).entrySet()) {
                cumulative.merge(entry.getKey(), entry.getValue(),
                        (a, b) -> (int) Math.min(RequestLimits.MAX_COUNT, (long) a + b));
            }
            tiers.add(new LinkedHashMap<>(cumulative));
        }
        return tiers;
    }
}
