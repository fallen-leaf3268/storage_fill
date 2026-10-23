package dev.airun.storagefill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class AllocationPlanner {
    private static final int MAX_NEEDS = 256;
    public static final int MAX_ALTERNATIVES = 4096;
    private static final int MAX_SEARCH_STEPS = 100_000;

    private AllocationPlanner() {
    }

    public record Need<K>(List<K> alternatives, int count, int limit, Map<K, Integer> capacities) {
        public Need(List<K> alternatives, int count, int limit) {
            this(alternatives, count, limit, Map.of());
        }

        public Need {
            capacities = capacities == null ? Map.of() : Map.copyOf(capacities);
        }

        public int capacity(K key) {
            return Math.min(limit, Math.max(0, capacities.getOrDefault(key, limit)));
        }
    }

    public record Choice<K>(K key, int count) {
    }

    public static <K> Optional<List<Choice<K>>> planByPriority(List<Need<K>> needs,
            Map<K, Integer> stock, Map<K, Integer> localStock, Map<K, Integer> networkStock,
            boolean maxTransfer) {
        return planByPriority(needs, stock, localStock, networkStock, maxTransfer, MAX_SEARCH_STEPS);
    }

    public static <K> Optional<List<Choice<K>>> planByPriority(List<Need<K>> needs,
            Map<K, Integer> stock, Map<K, Integer> localStock, Map<K, Integer> networkStock,
            boolean maxTransfer, boolean completeSets) {
        return planByPriority(needs, stock, localStock, networkStock, maxTransfer, completeSets,
                new SearchBudget(), MAX_SEARCH_STEPS);
    }

    public static <K> Optional<List<Choice<K>>> planByPriority(List<Need<K>> needs,
            Map<K, Integer> stock, Map<K, Integer> localStock, Map<K, Integer> networkStock,
            boolean maxTransfer, boolean completeSets, SearchBudget budget, int optimizationSteps) {
        if (localStock == null || networkStock == null) return Optional.empty();
        return planByPriority(needs, stock, List.of(localStock, networkStock), maxTransfer,
                completeSets, budget, optimizationSteps);
    }

    public static <K> Optional<List<Choice<K>>> planByPriority(List<Need<K>> needs,
            Map<K, Integer> stock, List<Map<K, Integer>> cumulativeTiers,
            boolean maxTransfer, boolean completeSets) {
        return planByPriority(needs, stock, cumulativeTiers, maxTransfer, completeSets,
                new SearchBudget(), MAX_SEARCH_STEPS);
    }

    public static <K> Optional<List<Choice<K>>> planByPriority(List<Need<K>> needs,
            Map<K, Integer> stock, List<Map<K, Integer>> cumulativeTiers,
            boolean maxTransfer, boolean completeSets, SearchBudget budget, int optimizationSteps) {
        Optional<List<Choice<K>>> combined = planByPriority(needs, stock, cumulativeTiers,
                maxTransfer, budget, optimizationSteps);
        if (!maxTransfer || completeSets || combined.isEmpty()) return combined;
        return Optional.of(expand(needs, stock, combined.get(), budget));
    }

    static <K> Optional<List<Choice<K>>> planByPriority(List<Need<K>> needs,
            Map<K, Integer> stock, Map<K, Integer> localStock, Map<K, Integer> networkStock,
            boolean maxTransfer, int optimizationSteps) {
        return planByPriority(needs, stock, localStock, networkStock, maxTransfer,
                new SearchBudget(), optimizationSteps);
    }

    public static <K> Optional<List<Choice<K>>> planByPriority(List<Need<K>> needs,
            Map<K, Integer> stock, Map<K, Integer> localStock, Map<K, Integer> networkStock,
            boolean maxTransfer, SearchBudget budget, int optimizationSteps) {
        return planByPriority(needs, stock, localStock, networkStock, maxTransfer, true,
                budget, optimizationSteps);
    }

    private static <K> Optional<List<Choice<K>>> planByPriority(List<Need<K>> needs,
            Map<K, Integer> stock, List<Map<K, Integer>> cumulativeTiers,
            boolean maxTransfer, SearchBudget budget, int optimizationSteps) {
        if (stock == null || cumulativeTiers == null || cumulativeTiers.size() > 4
                || cumulativeTiers.stream().anyMatch(tier -> tier == null)) return Optional.empty();
        Optional<List<Choice<K>>> combined = plan(needs, stock, maxTransfer, budget);
        if (combined.isEmpty()) return Optional.empty();
        List<Map<K, Integer>> tiers = new ArrayList<>(cumulativeTiers.size());
        for (Map<K, Integer> requested : cumulativeTiers) {
            Map<K, Integer> tier = new HashMap<>();
            for (K key : stock.keySet()) {
                int previous = tiers.isEmpty() ? 0 : available(tiers.get(tiers.size() - 1), key);
                tier.put(key, Math.min(available(stock, key), Math.max(previous, available(requested, key))));
            }
            tiers.add(tier);
        }
        List<Choice<K>> baseline = combined.get();
        if (tiers.isEmpty() || tiers.get(0).equals(stock)) return combined;
        List<Need<K>> fixed = new ArrayList<>(needs.size());
        for (int i = 0; i < needs.size(); i++) {
            Need<K> need = needs.get(i);
            fixed.add(new Need<>(need.alternatives(), baseline.get(i).count(), need.limit(), need.capacities()));
        }
        Optional<List<Choice<K>>> allLocal = plan(fixed, tiers.get(0), false, budget);
        if (allLocal.isPresent()) return allLocal;
        for (int i = 1; i < tiers.size(); i++) {
            Map<K, Integer> tier = tiers.get(i);
            if (tier.equals(tiers.get(i - 1))) continue;
            Optional<List<Choice<K>>> candidate = tier.equals(stock)
                    ? combined : plan(fixed, tier, false, budget);
            if (candidate.isPresent()) {
                baseline = candidate.get();
                break;
            }
        }
        return Optional.of(new PrioritySearch<>(fixed, stock, tiers, baseline,
                budget.child(Math.max(0, optimizationSteps))).run());
    }

    public static <K> Optional<List<Choice<K>>> planPreferLocal(List<Need<K>> needs,
            Map<K, Integer> stock, Map<K, Integer> localStock, boolean maxTransfer) {
        SearchBudget budget = new SearchBudget();
        SearchBudget localBudget = budget.child(10_000);
        Optional<List<Choice<K>>> local = plan(needs, localStock, maxTransfer, localBudget);
        if (!maxTransfer && local.isPresent()) return local;
        if (stock != null && stock.equals(localStock) && !localBudget.exhausted()) return local;
        Optional<List<Choice<K>>> combined = plan(needs, stock, maxTransfer, budget);
        if (local.isPresent() && (combined.isEmpty()
                || local.get().get(0).count() >= combined.get().get(0).count())) return local;
        return combined;
    }

    public static <K> Optional<List<Choice<K>>> plan(
            List<Need<K>> needs, Map<K, Integer> stock, boolean maxTransfer) {
        return plan(needs, stock, maxTransfer, new SearchBudget());
    }

    static <K> Optional<List<Choice<K>>> plan(
            List<Need<K>> needs, Map<K, Integer> stock, boolean maxTransfer, int searchSteps) {
        return plan(needs, stock, maxTransfer, new SearchBudget(Math.max(0, searchSteps)));
    }

    static <K> Optional<List<Choice<K>>> plan(
            List<Need<K>> needs, Map<K, Integer> stock, boolean maxTransfer, SearchBudget budget) {
        if (needs == null || stock == null || needs.isEmpty() || needs.size() > MAX_NEEDS) {
            return Optional.empty();
        }

        List<Slot<K>> slots = new ArrayList<>(needs.size());
        int alternativeCount = 0;
        int upperBound = maxTransfer ? 64 : 1;
        for (int index = 0; index < needs.size(); index++) {
            Need<K> need = needs.get(index);
            if (need == null || need.alternatives() == null || need.alternatives().isEmpty()
                    || need.count() <= 0 || need.limit() < need.count()) {
                return Optional.empty();
            }
            if (need.alternatives().size() > MAX_ALTERNATIVES - alternativeCount) {
                return Optional.empty();
            }
            alternativeCount += need.alternatives().size();
            List<Option<K>> alternatives = new ArrayList<>();
            int stockBound = 0;
            for (K key : new LinkedHashSet<>(need.alternatives())) {
                int capacity = need.capacity(key);
                alternatives.add(new Option<>(key, capacity));
                stockBound = Math.max(stockBound, Math.min(available(stock, key), capacity) / need.count());
            }
            upperBound = Math.min(upperBound, Math.min(need.limit() / need.count(), stockBound));
            slots.add(new Slot<>(index, alternatives, need.count()));
        }
        if (upperBound < 1) {
            return Optional.empty();
        }

        slots.sort(Comparator.comparingInt(slot -> slot.alternatives().size()));
        if (!maxTransfer) {
            return search(slots, stock, 1, budget);
        }

        Optional<List<Choice<K>>> best = search(slots, stock, 1, budget);
        if (best.isEmpty()) return best;
        int low = 2;
        int high = upperBound;
        while (low <= high && !budget.exhausted()) {
            int groups = low + (high - low) / 2;
            Optional<List<Choice<K>>> candidate = search(slots, stock, groups, budget);
            if (candidate.isPresent()) {
                best = candidate;
                low = groups + 1;
            } else {
                high = groups - 1;
            }
        }
        return best;
    }

    private static <K> Optional<List<Choice<K>>> search(
            List<Slot<K>> slots, Map<K, Integer> stock, int groups, SearchBudget budget) {
        if (!hasEnoughStock(slots, stock, groups)) return Optional.empty();
        Map<K, Integer> remaining = new HashMap<>();
        for (Map.Entry<K, Integer> entry : stock.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                remaining.put(entry.getKey(), entry.getValue());
            }
        }
        Object[] keys = new Object[slots.size()];
        if (!assign(slots, 0, groups, remaining, keys, budget)) {
            return Optional.empty();
        }
        Choice<K>[] choices = choiceArray(slots.size());
        for (int position = 0; position < slots.size(); position++) {
            Slot<K> slot = slots.get(position);
            @SuppressWarnings("unchecked")
            K key = (K) keys[position];
            choices[slot.originalIndex()] = new Choice<>(key, slot.count() * groups);
        }
        return Optional.of(List.copyOf(Arrays.asList(choices)));
    }

    private static <K> boolean assign(
            List<Slot<K>> slots,
            int position,
            int groups,
            Map<K, Integer> remaining,
            Object[] keys,
            SearchBudget budget) {
        if (!budget.takeStep()) {
            return false;
        }
        if (position == slots.size()) {
            return true;
        }
        Slot<K> slot = slots.get(position);
        long requiredLong = (long) slot.count() * groups;
        if (requiredLong > Integer.MAX_VALUE) {
            return false;
        }
        int required = (int) requiredLong;
        for (Option<K> option : slot.alternatives()) {
            if (!budget.takeStep()) return false;
            if (option.capacity() < required) continue;
            K key = option.key();
            int available = remaining.getOrDefault(key, 0);
            if (available < required) {
                continue;
            }
            remaining.put(key, available - required);
            keys[position] = key;
            if (assign(slots, position + 1, groups, remaining, keys, budget)) {
                return true;
            }
            remaining.put(key, available);
            if (budget.exhausted()) {
                return false;
            }
        }
        return false;
    }

    private static <K> boolean hasEnoughStock(List<Slot<K>> slots, Map<K, Integer> stock, int groups) {
        Map<Set<K>, Long> demands = new HashMap<>();
        Set<K> allKeys = new LinkedHashSet<>();
        long total = 0;
        for (Slot<K> slot : slots) {
            long required = (long) slot.count() * groups;
            if (required > Integer.MAX_VALUE) return false;
            Set<K> keys = new LinkedHashSet<>();
            for (Option<K> option : slot.alternatives()) {
                if (option.capacity() >= required && available(stock, option.key()) >= required) keys.add(option.key());
            }
            if (keys.isEmpty()) return false;
            demands.merge(keys, required, Long::sum);
            allKeys.addAll(keys);
            total += required;
        }
        if (!enough(stock, allKeys, total)) return false;
        boolean ambiguous = false;
        for (Map.Entry<Set<K>, Long> demand : demands.entrySet()) {
            if (!enough(stock, demand.getKey(), demand.getValue())) return false;
            ambiguous |= demand.getKey().size() > 1;
        }
        return !ambiguous || demands.size() < 3 || enoughConnectedStock(stock, allKeys, demands);
    }

    private static <K> boolean enoughConnectedStock(Map<K, Integer> stock, Set<K> keys,
            Map<Set<K>, Long> demands) {
        return new StockCheck<>(keys, demands).hasEnough(stock);
    }

    public static <K> StockCheck<K> stockCheck(List<Need<K>> needs) {
        Map<Set<K>, Long> demands = new HashMap<>();
        Set<K> keys = new LinkedHashSet<>();
        for (Need<K> need : needs) {
            Set<K> alternatives = new LinkedHashSet<>(need.alternatives());
            if (alternatives.isEmpty() || need.count() <= 0) throw new IllegalArgumentException("Invalid stock requirement");
            demands.merge(alternatives, (long) need.count(), Long::sum);
            keys.addAll(alternatives);
        }
        return new StockCheck<>(keys, demands);
    }

    public static final class StockCheck<K> {
        private final Map<K, Integer> groups;
        private final long[] required;

        private StockCheck(Set<K> keys, Map<Set<K>, Long> demands) {
            groups = new HashMap<>();
            int[] parents = new int[keys.size()];
            for (K key : keys) {
                int index = groups.size();
                groups.put(key, index);
                parents[index] = index;
            }
            for (Set<K> alternatives : demands.keySet()) {
                int first = -1;
                for (K key : alternatives) {
                    int root = root(parents, groups.get(key));
                    if (first < 0) first = root;
                    else parents[root] = root(parents, first);
                }
            }
            required = new long[parents.length];
            for (Map.Entry<Set<K>, Long> demand : demands.entrySet()) {
                required[root(parents, groups.get(demand.getKey().iterator().next()))] += demand.getValue();
            }
            groups.replaceAll((key, index) -> root(parents, index));
        }

        public boolean hasEnough(Map<K, ? extends Number> stock) {
            long[] remaining = required.clone();
            for (Map.Entry<K, Integer> entry : groups.entrySet()) {
                Number amount = stock.get(entry.getKey());
                if (amount != null && amount.longValue() > 0) {
                    int group = entry.getValue();
                    remaining[group] -= Math.min(remaining[group], amount.longValue());
                }
            }
            for (long amount : remaining) if (amount > 0) return false;
            return true;
        }
    }

    private static int root(int[] parents, int index) {
        while (parents[index] != index) {
            parents[index] = parents[parents[index]];
            index = parents[index];
        }
        return index;
    }

    private static <K> boolean enough(Map<K, Integer> stock, Set<K> keys, long required) {
        long total = 0;
        for (K key : keys) {
            total += available(stock, key);
            if (total >= required) return true;
        }
        return false;
    }

    private static <K> int available(Map<K, Integer> stock, K key) {
        Integer count = stock.get(key);
        return count == null || count < 0 ? 0 : count;
    }

    private static <K> List<Choice<K>> expand(List<Need<K>> needs, Map<K, Integer> stock,
            List<Choice<K>> baseline, SearchBudget budget) {
        Map<K, Integer> remaining = new HashMap<>();
        List<Choice<K>> expanded = new ArrayList<>(baseline);
        for (Choice<K> choice : baseline) {
            remaining.putIfAbsent(choice.key(), available(stock, choice.key()));
            remaining.computeIfPresent(choice.key(), (ignored, count) -> count - choice.count());
        }
        boolean changed;
        do {
            changed = false;
            for (int index = 0; index < needs.size(); index++) {
                if (!budget.takeStep()) return List.copyOf(expanded);
                Need<K> need = needs.get(index);
                Choice<K> choice = expanded.get(index);
                if ((long) choice.count() + need.count() > need.capacity(choice.key())) continue;
                int count = remaining.getOrDefault(choice.key(), 0);
                if (count < need.count()) continue;
                remaining.put(choice.key(), count - need.count());
                expanded.set(index, new Choice<>(choice.key(), choice.count() + need.count()));
                changed = true;
            }
        } while (changed);
        return List.copyOf(expanded);
    }

    @SuppressWarnings("unchecked")
    private static <K> Choice<K>[] choiceArray(int size) {
        return (Choice<K>[]) new Choice<?>[size];
    }

    private record Option<K>(K key, int capacity) {
    }

    private record Slot<K>(int originalIndex, List<Option<K>> alternatives, int count) {
    }

    private record Cost(long fourth, long third, long second, long first) implements Comparable<Cost> {
        Cost plus(Cost other) {
            return new Cost(fourth + other.fourth, third + other.third, second + other.second, first + other.first);
        }
        Cost minus(Cost other) {
            return new Cost(fourth - other.fourth, third - other.third, second - other.second, first - other.first);
        }
        public int compareTo(Cost other) {
            int compared = Long.compare(fourth, other.fourth);
            if (compared != 0) return compared;
            compared = Long.compare(third, other.third);
            if (compared != 0) return compared;
            compared = Long.compare(second, other.second);
            return compared != 0 ? compared : Long.compare(first, other.first);
        }
    }

    private static final class PrioritySearch<K> {
        private final List<Slot<K>> slots = new ArrayList<>();
        private final Map<K, Integer> stock;
        private final List<Map<K, Integer>> tiers;
        private final Map<K, Integer> used = new HashMap<>();
        private final Choice<K>[] choices;
        private final SearchBudget budget;
        private List<Choice<K>> best;
        private Cost bestCost;

        PrioritySearch(List<Need<K>> needs, Map<K, Integer> stock,
                List<Map<K, Integer>> tiers, List<Choice<K>> baseline, SearchBudget budget) {
            this.stock = stock;
            this.tiers = tiers;
            this.best = baseline;
            this.budget = budget;
            this.choices = choiceArray(needs.size());
            Map<K, Integer> totals = new HashMap<>();
            for (Choice<K> choice : baseline) totals.merge(choice.key(), choice.count(), Integer::sum);
            bestCost = new Cost(0, 0, 0, 0);
            for (Map.Entry<K, Integer> total : totals.entrySet()) {
                bestCost = bestCost.plus(cost(total.getKey(), total.getValue()));
            }
            for (int i = 0; i < needs.size(); i++) {
                Need<K> need = needs.get(i);
                List<Option<K>> alternatives = new ArrayList<>();
                for (K key : new LinkedHashSet<>(need.alternatives())) {
                    int capacity = need.capacity(key);
                    if (capacity >= need.count() && available(stock, key) >= need.count()) {
                        alternatives.add(new Option<>(key, capacity));
                    }
                }
                alternatives.sort(Comparator.comparing(option -> cost(option.key(), need.count())));
                slots.add(new Slot<>(i, alternatives, need.count()));
            }
            slots.sort(Comparator.comparingInt(slot -> slot.alternatives().size()));
        }

        List<Choice<K>> run() {
            assign(0, new Cost(0, 0, 0, 0));
            return best;
        }

        private Cost cost(K key, int count) {
            return new Cost(excess(key, count, 3), excess(key, count, 2),
                    excess(key, count, 1), excess(key, count, 0));
        }

        private long excess(K key, int count, int tier) {
            return tier < tiers.size() ? Math.max(0L, (long) count - available(tiers.get(tier), key)) : 0;
        }

        private Cost marginal(K key, int count) {
            int previous = used.getOrDefault(key, 0);
            Cost before = cost(key, previous);
            Cost after = cost(key, previous + count);
            return after.minus(before);
        }

        private boolean fits(K key, int count) {
            return (long) used.getOrDefault(key, 0) + count <= available(stock, key);
        }

        private void assign(int position, Cost current) {
            if (!budget.takeStep() || current.compareTo(bestCost) >= 0) return;
            if (position == slots.size()) {
                best = List.copyOf(Arrays.asList(choices.clone()));
                bestCost = current;
                return;
            }
            Cost lower = current;
            for (int i = position; i < slots.size(); i++) {
                Slot<K> future = slots.get(i);
                Cost minimum = null;
                for (Option<K> option : future.alternatives()) {
                    if (!budget.takeStep()) return;
                    K key = option.key();
                    if (!fits(key, future.count())) continue;
                    Cost candidate = marginal(key, future.count());
                    if (minimum == null || candidate.compareTo(minimum) < 0) minimum = candidate;
                }
                if (minimum == null) return;
                lower = lower.plus(minimum);
                if (lower.compareTo(bestCost) >= 0) return;
            }
            Slot<K> slot = slots.get(position);
            for (Option<K> option : slot.alternatives()) {
                if (!budget.takeStep()) return;
                K key = option.key();
                if (!fits(key, slot.count())) continue;
                int previous = used.getOrDefault(key, 0);
                Cost next = current.plus(marginal(key, slot.count()));
                used.put(key, previous + slot.count());
                choices[slot.originalIndex()] = new Choice<>(key, slot.count());
                assign(position + 1, next);
                if (previous == 0) used.remove(key); else used.put(key, previous);
                if (budget.exhausted()) return;
            }
        }
    }

    public static final class SearchBudget {
        private final int limit;
        private final SearchBudget parent;
        private int steps;
        private boolean exhausted;

        public SearchBudget() { this(MAX_SEARCH_STEPS); }
        public SearchBudget(int limit) { this(limit, null); }
        private SearchBudget(int limit, SearchBudget parent) {
            this.limit = limit;
            this.parent = parent;
        }

        SearchBudget child(int limit) { return new SearchBudget(Math.max(0, limit), this); }

        public int remaining() { return Math.min(limit - steps, parent == null ? Integer.MAX_VALUE : parent.remaining()); }

        private boolean takeStep() {
            if (steps >= limit || (parent != null && !parent.takeStep())) {
                exhausted = true;
                return false;
            }
            steps++;
            return true;
        }

        public boolean exhausted() {
            return exhausted || (parent != null && parent.exhausted());
        }
    }
}
