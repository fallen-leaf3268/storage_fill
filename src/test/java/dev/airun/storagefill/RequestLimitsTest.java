package dev.airun.storagefill;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestLimitsTest {
    @Test void rejectsOverlappingSourcesAndTargets() {
        assertFalse(RequestLimits.validSlots(10, new int[]{1, 2}, new int[]{2, 3}));
    }
    @Test void rejectsDuplicateTargetsAndOutOfBounds() {
        assertFalse(RequestLimits.validSlots(10, new int[]{1, 1}, new int[]{3}));
        assertFalse(RequestLimits.validSlots(10, new int[]{10}, new int[]{3}));
        assertFalse(RequestLimits.validSlots(10, new int[]{-1}, new int[]{3}));
    }
    @Test void acceptsDisjointValidSlots() {
        assertTrue(RequestLimits.validSlots(46, new int[]{1,2,3,4}, new int[]{9,10,11}));
    }
    @Test void rejectsEmptyOrOversizedTargets() {
        assertFalse(RequestLimits.validSlots(100, new int[0], new int[]{90}));
        assertFalse(RequestLimits.validSlots(100, new int[82], new int[]{90}));
    }
}
