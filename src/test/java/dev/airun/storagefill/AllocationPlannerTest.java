package dev.airun.storagefill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AllocationPlannerTest {
    @Test
    void storageNetworksWinOverBackpackAlternatives() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A", "B"), 1, 64),
                new AllocationPlanner.Need<>(List.of("A", "C"), 1, 64));
        var result = AllocationPlanner.planByPriority(needs, Map.of("A", 1, "B", 1, "C", 1),
                Map.of(), Map.of("A", 1, "B", 1), false);
        assertEquals(List.of(new AllocationPlanner.Choice<>("B", 1), new AllocationPlanner.Choice<>("A", 1)), result.orElseThrow());
    }

    @Test
    void minimizesBackpackUseWhenOneBackpackIngredientIsUnavoidable() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A", "B"), 1, 64),
                new AllocationPlanner.Need<>(List.of("A", "C"), 1, 64),
                new AllocationPlanner.Need<>(List.of("D"), 1, 64));
        var result = AllocationPlanner.planByPriority(needs, Map.of("A", 1, "B", 1, "C", 1, "D", 1),
                Map.of(), Map.of("A", 1, "B", 1), false);
        assertEquals(List.of(new AllocationPlanner.Choice<>("B", 1), new AllocationPlanner.Choice<>("A", 1),
                new AllocationPlanner.Choice<>("D", 1)), result.orElseThrow());
    }

    @Test
    void prioritizesLargestGroupsBeforeBackpackCost() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("network", "backpack"), 1, 64));
        var result = AllocationPlanner.planByPriority(needs, Map.of("network", 1, "backpack", 8),
                Map.of("network", 1), Map.of("network", 1), true);
        assertEquals(List.of(new AllocationPlanner.Choice<>("backpack", 8)), result.orElseThrow());
    }

    @Test
    void minimizesRemoteCostAfterBackpackCost() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A", "B"), 1, 64),
                new AllocationPlanner.Need<>(List.of("A", "C"), 1, 64),
                new AllocationPlanner.Need<>(List.of("D"), 1, 64));
        var result = AllocationPlanner.planByPriority(needs, Map.of("A", 1, "B", 1, "C", 1, "D", 1),
                Map.of("A", 1, "B", 1), Map.of("A", 1, "B", 1, "C", 1), false);
        assertEquals(List.of(new AllocationPlanner.Choice<>("B", 1), new AllocationPlanner.Choice<>("A", 1),
                new AllocationPlanner.Choice<>("D", 1)), result.orElseThrow());
    }

    @Test
    void countsTierCostAcrossRepeatedKeysRatherThanPerSlot() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A", "B"), 2, 64),
                new AllocationPlanner.Need<>(List.of("A", "C"), 2, 64),
                new AllocationPlanner.Need<>(List.of("D"), 1, 64));
        var result = AllocationPlanner.planByPriority(needs, Map.of("A", 4, "B", 2, "C", 2, "D", 1),
                Map.of(), Map.of("A", 2, "B", 2), false);
        assertEquals(List.of(new AllocationPlanner.Choice<>("B", 2), new AllocationPlanner.Choice<>("A", 2),
                new AllocationPlanner.Choice<>("D", 1)), result.orElseThrow());
    }

    @Test
    void priorityPlanningPreservesCallerCollections() {
        var alternatives = new ArrayList<>(List.of("backpack", "network", "local"));
        var needs = List.of(new AllocationPlanner.Need<>(alternatives, 1, 64));
        var stock = new LinkedHashMap<>(Map.of("backpack", 2, "network", 2, "local", 2));
        var local = new LinkedHashMap<>(Map.of("local", 2));
        var network = new LinkedHashMap<>(Map.of("local", 2, "network", 2));
        var result = AllocationPlanner.planByPriority(needs, stock, local, network, false);
        assertEquals(List.of(new AllocationPlanner.Choice<>("local", 1)), result.orElseThrow());
        assertEquals(List.of("backpack", "network", "local"), alternatives);
        assertEquals(Map.of("backpack", 2, "network", 2, "local", 2), stock);
        assertEquals(Map.of("local", 2), local);
        assertEquals(Map.of("local", 2, "network", 2), network);
    }

    @Test
    void exhaustedOptimizationKeepsAFeasibleMaximumGroupPlan() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A", "B"), 1, 64),
                new AllocationPlanner.Need<>(List.of("A", "C"), 1, 64),
                new AllocationPlanner.Need<>(List.of("D"), 1, 64));
        var stock = Map.of("A", 2, "B", 2, "C", 2, "D", 2);
        var result = AllocationPlanner.planByPriority(needs, stock, Map.of(),
                Map.of("A", 2, "B", 2), true, 0).orElseThrow();
        Map<String, Integer> used = new LinkedHashMap<>();
        for (int i = 0; i < result.size(); i++) {
            var choice = result.get(i);
            assertEquals(2, choice.count());
            assertTrue(needs.get(i).alternatives().contains(choice.key()));
            used.merge(choice.key(), choice.count(), Integer::sum);
        }
        used.forEach((key, amount) -> assertTrue(amount <= stock.get(key)));
    }

    @Test
    void networkOnlyBaselineSurvivesZeroOptimizationBudget() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A", "B"), 1, 64),
                new AllocationPlanner.Need<>(List.of("A", "C"), 1, 64));
        var result = AllocationPlanner.planByPriority(needs, Map.of("A", 1, "B", 1, "C", 1),
                Map.of(), Map.of("A", 1, "B", 1), false, 0);
        assertEquals(List.of(new AllocationPlanner.Choice<>("B", 1), new AllocationPlanner.Choice<>("A", 1)),
                result.orElseThrow());
    }

    @Test
    void repeatedPriorityCallsShareOneTotalSearchBudget() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("iron"), 1, 64));
        var stock = Map.of("iron", 64);
        var budget = new AllocationPlanner.SearchBudget(6);
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 1)),
                AllocationPlanner.planByPriority(needs, stock, stock, stock, false, budget, 100).orElseThrow());
        assertEquals(3, budget.remaining());
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 1)),
                AllocationPlanner.planByPriority(needs, stock, stock, stock, false, true, budget, 100).orElseThrow());
        assertEquals(0, budget.remaining());
        assertTrue(AllocationPlanner.planByPriority(needs, stock, stock, stock, false, budget, 100).isEmpty());
        assertTrue(budget.exhausted());
        assertEquals(0, budget.remaining());
        assertTrue(AllocationPlanner.planByPriority(needs, stock, stock, stock, false, false, budget, 100).isEmpty());
        assertEquals(0, budget.remaining());
    }

    @Test
    void emptyResultDistinguishesStockShortageFromExhaustedSearch() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("iron"), 1, 64));
        var shortageBudget = new AllocationPlanner.SearchBudget(6);
        assertTrue(AllocationPlanner.planByPriority(needs, Map.of(), Map.of(), Map.of(), false,
                shortageBudget, 100).isEmpty());
        assertFalse(shortageBudget.exhausted());
        assertEquals(6, shortageBudget.remaining());
        var searchBudget = new AllocationPlanner.SearchBudget(2);
        var stock = Map.of("iron", 1);
        assertTrue(AllocationPlanner.planByPriority(needs, stock, stock, stock, false,
                searchBudget, 100).isEmpty());
        assertTrue(searchBudget.exhausted());
        assertEquals(0, searchBudget.remaining());
    }

    @Test
    void sharedBudgetExhaustionDuringOptimizationRetainsAFeasibleBaseline() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("remote", "backpack"), 1, 64));
        var stock = Map.of("remote", 1, "backpack", 1);
        var budget = new AllocationPlanner.SearchBudget(4);
        var result = AllocationPlanner.planByPriority(needs, stock, Map.of(), Map.of(), false, budget, 100).orElseThrow();
        assertEquals(List.of(new AllocationPlanner.Choice<>("remote", 1)), result);
        assertTrue(budget.exhausted());
        assertEquals(0, budget.remaining());
        assertTrue(result.stream().allMatch(choice -> choice.count() <= stock.get(choice.key())));
    }

    @Test
    void sharedBudgetExhaustionDoesNotDiscardTheFirstNonCompleteShiftGroup() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("iron"), 1, 64));
        var stock = Map.of("iron", 64);
        var budget = new AllocationPlanner.SearchBudget(3);
        var result = AllocationPlanner.planByPriority(needs, stock, stock, stock, true, false, budget, 100).orElseThrow();
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 1)), result);
        assertTrue(budget.exhausted());
        assertEquals(0, budget.remaining());
    }

    @Test
    void clampsPriorityTiersToActualStock() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A"), 1, 64));
        var result = AllocationPlanner.planByPriority(needs, Map.of("A", 2),
                Map.of("A", 100), Map.of("A", 200), true);
        assertEquals(List.of(new AllocationPlanner.Choice<>("A", 2)), result.orElseThrow());
    }
    @Test
    void usesLocalAlternativeCombinationBeforeRemoteMaterial() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A", "B"), 1, 64),
                new AllocationPlanner.Need<>(List.of("A", "C"), 1, 64));
        var result = AllocationPlanner.planPreferLocal(needs, Map.of("A", 1, "B", 1, "C", 1), Map.of("A", 1, "B", 1), false);
        assertEquals(List.of(new AllocationPlanner.Choice<>("B", 1), new AllocationPlanner.Choice<>("A", 1)),
                result.orElseThrow());
    }

    @Test
    void maximumTransferUsesRemoteWhenItProvidesMoreCompleteGroups() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A"), 1, 64));
        var result = AllocationPlanner.planPreferLocal(needs, Map.of("A", 5), Map.of("A", 1), true);
        assertEquals(List.of(new AllocationPlanner.Choice<>("A", 5)), result.orElseThrow());
    }

    @Test
    void maximumTransferPrefersLocalWhenRemoteCannotAddACompleteGroup() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A", "B"), 1, 64),
                new AllocationPlanner.Need<>(List.of("A", "C"), 1, 64));
        var result = AllocationPlanner.planPreferLocal(needs, Map.of("A", 1, "B", 1, "C", 1), Map.of("A", 1, "B", 1), true);
        assertEquals(List.of(new AllocationPlanner.Choice<>("B", 1), new AllocationPlanner.Choice<>("A", 1)), result.orElseThrow());
    }

    @Test
    void plansOneCompleteGroupForNormalTransfer() {
        var result = AllocationPlanner.plan(
                List.of(new AllocationPlanner.Need<>(List.of("iron"), 3, 64)),
                Map.of("iron", 10),
                false);

        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 3)), result.orElseThrow());
    }

    @Test
    void plansLargestCompleteTransferUpToSixtyFourGroups() {
        var result = AllocationPlanner.plan(
                List.of(new AllocationPlanner.Need<>(List.of("iron"), 2, 200)),
                Map.of("iron", 1000),
                true);

        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 128)), result.orElseThrow());
    }

    @Test
    void slotCapacityLimitsMaximumTransfer() {
        var result = AllocationPlanner.plan(
                List.of(new AllocationPlanner.Need<>(List.of("iron"), 3, 10)),
                Map.of("iron", 100),
                true);

        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 9)), result.orElseThrow());
    }

    @Test
    void unselectedAlternativeDoesNotLimitStackableCandidate() {
        var need = new AllocationPlanner.Need<>(List.of("iron", "sword"), 1, 64,
                Map.of("iron", 64, "sword", 1));
        var result = AllocationPlanner.plan(List.of(need), Map.of("iron", 64, "sword", 1), true);
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 64)), result.orElseThrow());
    }

    @Test
    void pooledUnstackableStockDoesNotExceedItsCandidateCapacity() {
        var need = new AllocationPlanner.Need<>(List.of("iron", "sword"), 1, 64,
                Map.of("iron", 64, "sword", 1));
        var result = AllocationPlanner.plan(List.of(need), Map.of("sword", 64), true);
        assertEquals(List.of(new AllocationPlanner.Choice<>("sword", 1)), result.orElseThrow());
    }

    @Test
    void eachSlotKeepsItsOwnAlternativeCapacity() {
        var needs = List.of(
                new AllocationPlanner.Need<>(List.of("A", "B"), 1, 64, Map.of("A", 64, "B", 1)),
                new AllocationPlanner.Need<>(List.of("A", "B"), 1, 64, Map.of("A", 1, "B", 64)));
        var result = AllocationPlanner.plan(needs, Map.of("A", 64, "B", 64), true);
        assertEquals(List.of(new AllocationPlanner.Choice<>("A", 64), new AllocationPlanner.Choice<>("B", 64)),
                result.orElseThrow());
    }

    @Test
    void priorityOptimizationCannotReplaceMaximumWithAnUndersizedAlternative() {
        var need = new AllocationPlanner.Need<>(List.of("local", "remote"), 1, 64,
                Map.of("local", 1, "remote", 64));
        var result = AllocationPlanner.planByPriority(List.of(need), Map.of("local", 64, "remote", 64),
                Map.of("local", 64), Map.of("local", 64), true);
        assertEquals(List.of(new AllocationPlanner.Choice<>("remote", 64)), result.orElseThrow());
    }

    @Test
    void exhaustedMaximumSearchKeepsItsPreviouslyValidatedGroup() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A"), 1, 64));
        var result = AllocationPlanner.plan(needs, Map.of("A", 64), true, 3);
        assertEquals(List.of(new AllocationPlanner.Choice<>("A", 1)), result.orElseThrow());
    }

    @Test
    void sharedAlternativeShortageDoesNotSpendAnExponentialSearchBudget() {
        ProbeKey first = new ProbeKey("A"), second = new ProbeKey("B");
        var needs = Collections.nCopies(36, new AllocationPlanner.Need<>(List.of(first, second), 1, 64));
        Map<ProbeKey, Integer> stock = Map.of(first, 17, second, 17);
        first.hashes = second.hashes = 0;
        assertTrue(AllocationPlanner.planByPriority(needs, stock, stock, stock, false).isEmpty());
        assertTrue(first.hashes + second.hashes < 5000, "Shared shortage repeatedly searched equivalent alternative combinations");
    }

    @Test
    void unrelatedSurplusDoesNotHideASharedAlternativeShortage() {
        ProbeKey first = new ProbeKey("A"), second = new ProbeKey("B"), surplus = new ProbeKey("surplus");
        var needs = new ArrayList<>(Collections.nCopies(36,
                new AllocationPlanner.Need<>(List.of(first, second), 1, 64)));
        needs.add(new AllocationPlanner.Need<>(List.of(surplus), 1, 64));
        Map<ProbeKey, Integer> stock = Map.of(first, 17, second, 17, surplus, 100);
        first.hashes = second.hashes = surplus.hashes = 0;
        assertTrue(AllocationPlanner.plan(needs, stock, false).isEmpty());
        assertTrue(first.hashes + second.hashes + surplus.hashes < 5000,
                "Unrelated stock bypassed the repeated alternative shortage check");
    }

    @Test
    void sharedMaximumStockKeepsOneCompleteGroup() {
        var needs = Collections.nCopies(36, new AllocationPlanner.Need<>(List.of("A", "B"), 1, 2));
        var result = AllocationPlanner.plan(needs, Map.of("A", 35, "B", 35), true).orElseThrow();
        assertEquals(36, result.size());
        assertTrue(result.stream().allMatch(choice -> choice.count() == 1));
        assertTrue(result.stream().filter(choice -> choice.key().equals("A")).count() <= 35);
        assertTrue(result.stream().filter(choice -> choice.key().equals("B")).count() <= 35);
    }

    @Test
    void priorityPhasesShareOneBudgetAndKeepTheFeasibleBaseline() {
        ProbeKey first = new ProbeKey("A"), shared = new ProbeKey("B"), third = new ProbeKey("C"), surplus = new ProbeKey("D");
        var needs = new ArrayList<>(Collections.nCopies(14,
                new AllocationPlanner.Need<>(List.of(first, shared), 1, 64)));
        needs.addAll(Collections.nCopies(14, new AllocationPlanner.Need<>(List.of(shared, third), 1, 64)));
        needs.add(new AllocationPlanner.Need<>(List.of(surplus), 1, 64));
        Map<ProbeKey, Integer> stock = Map.of(first, 14, shared, 14, third, 14, surplus, 100);
        Map<ProbeKey, Integer> local = Map.of(first, 8, shared, 8, third, 8, surplus, 100);
        AllocationPlanner.SearchBudget budget = new AllocationPlanner.SearchBudget(100);
        first.hashes = shared.hashes = third.hashes = surplus.hashes = 0;
        var result = AllocationPlanner.planByPriority(needs, stock, local, stock, false, budget, 100_000).orElseThrow();
        assertEquals(29, result.size());
        assertEquals(0, budget.remaining());
        assertTrue(first.hashes + shared.hashes + third.hashes + surplus.hashes < 5000,
                "Priority phases restarted expensive searches after exhausting the shared budget");
        Map<ProbeKey, Integer> used = new LinkedHashMap<>();
        result.forEach(choice -> used.merge(choice.key(), choice.count(), Integer::sum));
        used.forEach((key, amount) -> assertTrue(amount <= stock.get(key)));
    }

    @Test
    void candidateCapacityIsCopiedAndClampedToTheSlotLimit() {
        var capacities = new LinkedHashMap<>(Map.of("A", 128, "B", 1));
        var need = new AllocationPlanner.Need<>(List.of("A", "B"), 1, 64, capacities);
        capacities.put("B", 64);
        assertEquals(64, need.capacity("A"));
        assertEquals(1, need.capacity("B"));
        assertEquals(64, new AllocationPlanner.Need<>(List.of("A"), 1, 64).capacity("A"));
    }

    @Test
    void nonCompleteMaximumFillsEachIngredientToItsOwnStock() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("iron"), 1, 64),
                new AllocationPlanner.Need<>(List.of("gold"), 1, 64));
        var stock = Map.of("iron", 64, "gold", 1);
        var result = AllocationPlanner.planByPriority(needs, stock, stock, stock, true, false);
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 64), new AllocationPlanner.Choice<>("gold", 1)),
                result.orElseThrow());
    }

    @Test
    void nonCompleteMaximumSharesStockInOriginalSlotRounds() {
        var needs = Collections.nCopies(2, new AllocationPlanner.Need<>(List.of("iron"), 1, 64));
        var stock = Map.of("iron", 65);
        var result = AllocationPlanner.planByPriority(needs, stock, stock, stock, true, false);
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 33), new AllocationPlanner.Choice<>("iron", 32)),
                result.orElseThrow());
    }

    @Test
    void nonCompleteMaximumRespectsEachSameItemSlotCapacity() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("iron"), 1, 1),
                new AllocationPlanner.Need<>(List.of("iron"), 1, 64));
        var stock = Map.of("iron", 65);
        var result = AllocationPlanner.planByPriority(needs, stock, stock, stock, true, false);
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 1), new AllocationPlanner.Choice<>("iron", 64)),
                result.orElseThrow());
    }

    @Test
    void nonCompleteMaximumPreservesWholeIngredientUnits() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("iron"), 3, 64),
                new AllocationPlanner.Need<>(List.of("sword"), 1, 1));
        var stock = Map.of("iron", 100, "sword", 1);
        var result = AllocationPlanner.planByPriority(needs, stock, stock, stock, true, false);
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 63), new AllocationPlanner.Choice<>("sword", 1)),
                result.orElseThrow());
    }

    @Test
    void nonMaximumStillUsesOneCompleteGroupWhenCompleteSetsIsFalse() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("iron"), 2, 64),
                new AllocationPlanner.Need<>(List.of("gold"), 1, 64));
        var enough = Map.of("iron", 64, "gold", 64);
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 2), new AllocationPlanner.Choice<>("gold", 1)),
                AllocationPlanner.planByPriority(needs, enough, enough, enough, false, false).orElseThrow());
        var missing = Map.of("iron", 1, "gold", 64);
        assertTrue(AllocationPlanner.planByPriority(needs, missing, missing, missing, false, false).isEmpty());
    }

    @Test
    void explicitCompleteMaximumKeepsBalancedGroups() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("iron"), 1, 64),
                new AllocationPlanner.Need<>(List.of("gold"), 1, 64));
        var stock = Map.of("iron", 64, "gold", 1);
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 1), new AllocationPlanner.Choice<>("gold", 1)),
                AllocationPlanner.planByPriority(needs, stock, stock, stock, true, true).orElseThrow());
    }

    @Test
    void nonCompleteMaximumKeepsItsInitiallyPreferredAlternative() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("network", "local"), 1, 64),
                new AllocationPlanner.Need<>(List.of("catalyst"), 1, 64));
        var stock = Map.of("network", 64, "local", 1, "catalyst", 1);
        var local = Map.of("local", 1, "catalyst", 1);
        assertEquals(List.of(new AllocationPlanner.Choice<>("local", 1), new AllocationPlanner.Choice<>("catalyst", 1)),
                AllocationPlanner.planByPriority(needs, stock, local, stock, true, false).orElseThrow());
    }

    @Test
    void nonCompleteExpansionKeepsTheValidatedGroupWhenItsBudgetEnds() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("iron"), 1, 64),
                new AllocationPlanner.Need<>(List.of("gold"), 1, 64));
        var stock = Map.of("iron", 64, "gold", 1);
        var budget = new AllocationPlanner.SearchBudget(5);
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 1), new AllocationPlanner.Choice<>("gold", 1)),
                AllocationPlanner.planByPriority(needs, stock, stock, stock, true, false, budget, 100_000).orElseThrow());
        assertEquals(0, budget.remaining());
    }

    @Test
    void nonCompleteMaximumUsesTheSelectedAlternativeCapacity() {
        var need = new AllocationPlanner.Need<>(List.of("iron", "sword"), 1, 64,
                Map.of("iron", 64, "sword", 1));
        var stock = Map.of("iron", 64, "sword", 1);
        assertEquals(List.of(new AllocationPlanner.Choice<>("iron", 64)),
                AllocationPlanner.planByPriority(List.of(need), stock, stock, stock, true, false).orElseThrow());
    }

    @Test
    void backtracksAcrossOverlappingAlternatives() {
        var result = AllocationPlanner.plan(
                List.of(
                        new AllocationPlanner.Need<>(List.of("iron", "gold"), 1, 64),
                        new AllocationPlanner.Need<>(List.of("iron"), 1, 64)),
                Map.of("iron", 1, "gold", 1),
                false);

        assertEquals(
                List.of(
                        new AllocationPlanner.Choice<>("gold", 1),
                        new AllocationPlanner.Choice<>("iron", 1)),
                result.orElseThrow());
    }

    @Test
    void consumesSharedStockAcrossSlots() {
        var result = AllocationPlanner.plan(
                List.of(
                        new AllocationPlanner.Need<>(List.of("iron"), 2, 64),
                        new AllocationPlanner.Need<>(List.of("iron"), 2, 64)),
                Map.of("iron", 3),
                false);

        assertTrue(result.isEmpty());
    }

    @Test
    void doesNotSplitOneSlotAcrossAlternatives() {
        var result = AllocationPlanner.plan(
                List.of(new AllocationPlanner.Need<>(List.of("iron", "gold"), 3, 64)),
                Map.of("iron", 2, "gold", 2),
                false);

        assertTrue(result.isEmpty());
    }

    @Test
    void maximumTransferRequiresAllSlotsForEveryGroup() {
        var result = AllocationPlanner.plan(
                List.of(
                        new AllocationPlanner.Need<>(List.of("iron", "gold"), 2, 128),
                        new AllocationPlanner.Need<>(List.of("iron"), 1, 128)),
                Map.of("iron", 3, "gold", 4),
                true);

        assertEquals(
                List.of(
                        new AllocationPlanner.Choice<>("gold", 4),
                        new AllocationPlanner.Choice<>("iron", 2)),
                result.orElseThrow());
    }

    @Test
    void returnsEmptyForNoSlotsOrImpossibleAndInvalidSlots() {
        assertTrue(AllocationPlanner.plan(List.of(), Map.of(), false).isEmpty());
        assertTrue(AllocationPlanner.plan(
                List.of(new AllocationPlanner.Need<String>(List.of(), 1, 64)), Map.of(), false).isEmpty());
        assertTrue(AllocationPlanner.plan(
                List.of(new AllocationPlanner.Need<>(List.of("iron"), 0, 64)), Map.of("iron", 1), false).isEmpty());
        assertTrue(AllocationPlanner.plan(
                List.of(new AllocationPlanner.Need<>(List.of("iron"), 2, 1)), Map.of("iron", 2), false).isEmpty());
    }

    @Test
    void treatsMissingAndNegativeStockAsUnavailable() {
        assertTrue(AllocationPlanner.plan(
                List.of(new AllocationPlanner.Need<>(List.of("iron"), 1, 64)), Map.of(), false).isEmpty());
        assertTrue(AllocationPlanner.plan(
                List.of(new AllocationPlanner.Need<>(List.of("iron"), 1, 64)), Map.of("iron", -1), false).isEmpty());
    }

    @Test
    void leavesCallerOwnedCollectionsUnchanged() {
        var alternatives = new ArrayList<>(List.of("iron", "gold"));
        var needs = new ArrayList<>(List.of(new AllocationPlanner.Need<>(alternatives, 1, 64)));
        var stock = new LinkedHashMap<>(Map.of("iron", 1, "gold", 1));
        var originalNeeds = List.copyOf(needs);
        var originalStock = Map.copyOf(stock);

        var result = AllocationPlanner.plan(needs, stock, false);

        assertTrue(result.isPresent());
        assertEquals(originalNeeds, needs);
        assertEquals(List.of("iron", "gold"), alternatives);
        assertEquals(originalStock, stock);
        assertFalse(result.orElseThrow().isEmpty());
    }

    @Test
    void rejectsOversizedSearchInput() {
        var alternatives = Collections.nCopies(4097, "iron");

        var result = AllocationPlanner.plan(
                List.of(new AllocationPlanner.Need<>(alternatives, 1, 64)), Map.of("iron", 1), false);

        assertTrue(result.isEmpty());
    }

    @Test
    void rejectsConnectedAlternativeShortageBeforeExhaustingTheBudget() {
        var needs = ambiguousCluster(1);
        var stock = Map.of("A", 9, "B", 10, "C", 10, "D", 20);
        var budget = new AllocationPlanner.SearchBudget(1000);
        assertTrue(AllocationPlanner.plan(needs, stock, false, budget).isEmpty());
        assertFalse(budget.exhausted());
        assertTrue(budget.remaining() > 900);
    }

    @Test
    void connectedAlternativeCapacityScalesWithSlotRequirements() {
        var needs = ambiguousCluster(2);
        var stock = Map.of("A", 19, "B", 20, "C", 20, "D", 40);
        var budget = new AllocationPlanner.SearchBudget(1000);
        assertTrue(AllocationPlanner.plan(needs, stock, false, budget).isEmpty());
        assertFalse(budget.exhausted());
        assertTrue(budget.remaining() > 900);
    }

    @Test
    void connectedCapacityDoesNotRejectAFeasibleFirstChoiceOrder() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A", "B"), 1, 64),
                new AllocationPlanner.Need<>(List.of("A", "C"), 1, 64),
                new AllocationPlanner.Need<>(List.of("B", "C"), 1, 64),
                new AllocationPlanner.Need<>(List.of("D"), 1, 64));
        assertEquals(List.of(new AllocationPlanner.Choice<>("A", 1),
                new AllocationPlanner.Choice<>("C", 1), new AllocationPlanner.Choice<>("B", 1),
                new AllocationPlanner.Choice<>("D", 1)),
                AllocationPlanner.plan(needs, Map.of("A", 1, "B", 1, "C", 1, "D", 20), false).orElseThrow());
    }

    @Test
    void connectedCapacityKeepsDistinctVariantsAndPerKeySlotLimits() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("A:one", "A:two"), 3, 64),
                new AllocationPlanner.Need<>(List.of("A:one", "B"), 3, 64, Map.of("A:one", 2)),
                new AllocationPlanner.Need<>(List.of("B", "C"), 3, 64),
                new AllocationPlanner.Need<>(List.of("D"), 1, 64));
        assertTrue(AllocationPlanner.plan(needs,
                Map.of("A:one", 2, "A:two", 2, "B", 6, "C", 3, "D", 64), false).isEmpty());
        assertEquals(List.of(new AllocationPlanner.Choice<>("A:two", 3),
                new AllocationPlanner.Choice<>("B", 3), new AllocationPlanner.Choice<>("B", 3),
                new AllocationPlanner.Choice<>("D", 1)), AllocationPlanner.plan(needs,
                Map.of("A:one", 2, "A:two", 3, "B", 6, "C", 3, "D", 64), false).orElseThrow());
    }

    private static List<AllocationPlanner.Need<String>> ambiguousCluster(int count) {
        List<AllocationPlanner.Need<String>> needs = new ArrayList<>();
        for (List<String> keys : List.of(List.of("A", "B"), List.of("A", "C"), List.of("B", "C"))) {
            for (int i = 0; i < 10; i++) needs.add(new AllocationPlanner.Need<>(keys, count, 64));
        }
        for (int i = 0; i < 6; i++) needs.add(new AllocationPlanner.Need<>(List.of("D"), count, 64));
        return needs;
    }

    @Test
    void capacityPruningMatchesExhaustiveAssignments() {
        Random random = new Random(0x51A71L);
        List<String> keys = List.of("A", "B", "C", "D");
        for (int sample = 0; sample < 128; sample++) {
            Map<String, Integer> stock = new LinkedHashMap<>();
            for (String key : keys) stock.put(key, random.nextInt(8));
            List<AllocationPlanner.Need<String>> needs = new ArrayList<>();
            int slots = 1 + random.nextInt(6);
            for (int slot = 0; slot < slots; slot++) {
                List<String> alternatives = new ArrayList<>(keys);
                Collections.shuffle(alternatives, random);
                alternatives = alternatives.subList(0, 1 + random.nextInt(4));
                Map<String, Integer> capacities = new LinkedHashMap<>();
                for (String key : alternatives) capacities.put(key, random.nextInt(6));
                needs.add(new AllocationPlanner.Need<>(List.copyOf(alternatives), 1 + random.nextInt(2), 5, capacities));
            }
            int maximum = 0;
            for (int groups = 1; groups <= 5; groups++) {
                if (canAssign(needs, new LinkedHashMap<>(stock), 0, groups)) maximum = groups;
            }
            assertEquals(maximum > 0, AllocationPlanner.plan(needs, stock, false).isPresent());
            var shift = AllocationPlanner.plan(needs, stock, true);
            assertEquals(maximum > 0, shift.isPresent());
            if (shift.isPresent()) {
                for (int slot = 0; slot < slots; slot++) {
                    assertEquals(maximum * needs.get(slot).count(), shift.get().get(slot).count());
                }
            }
        }
    }

    private static boolean canAssign(List<AllocationPlanner.Need<String>> needs, Map<String, Integer> stock,
            int position, int groups) {
        if (position == needs.size()) return true;
        var need = needs.get(position);
        int amount = need.count() * groups;
        for (String key : need.alternatives()) {
            int remaining = stock.getOrDefault(key, 0);
            if (need.capacity(key) < amount || remaining < amount) continue;
            stock.put(key, remaining - amount);
            if (canAssign(needs, stock, position + 1, groups)) return true;
            stock.put(key, remaining);
        }
        return false;
    }

    @Test
    void compiledStockCheckReusesRequirementsAndReadsUpdatedQuantities() {
        var check = AllocationPlanner.stockCheck(ambiguousCluster(1));
        assertFalse(check.hasEnough(Map.of("A", 9, "B", 10, "C", 10, "D", 20)));
        assertTrue(check.hasEnough(Map.of("A", 10L, "B", 10L, "C", 10L, "D", 20L)));
        assertFalse(check.hasEnough(Map.of("A", 9L, "B", 10L, "C", 10L, "D", Long.MAX_VALUE)));
    }

    @Test
    void compiledStockCheckIsAnUpperBoundAndDoesNotReplaceSlotMatching() {
        var needs = List.of(new AllocationPlanner.Need<>(List.of("one", "two"), 3, 64));
        var stock = Map.of("one", 2, "two", 2);
        assertTrue(AllocationPlanner.stockCheck(needs).hasEnough(stock));
        assertTrue(AllocationPlanner.plan(needs, stock, false).isEmpty());
    }

    @Test
    void compiledStockCheckDoesNotOverflowOnLargeAvailableAmounts() {
        var needs = Collections.nCopies(3, new AllocationPlanner.Need<>(List.of("A", "B"), Integer.MAX_VALUE, Integer.MAX_VALUE));
        var check = AllocationPlanner.stockCheck(needs);
        assertFalse(check.hasEnough(Map.of("A", (long) Integer.MAX_VALUE, "B", (long) Integer.MAX_VALUE)));
        assertTrue(check.hasEnough(Map.of("A", Long.MAX_VALUE)));
    }

    private static final class ProbeKey {
        private final String value;
        private int hashes;

        private ProbeKey(String value) { this.value = value; }

        @Override
        public int hashCode() {
            hashes++;
            return value.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ProbeKey key && value.equals(key.value);
        }
    }
}
