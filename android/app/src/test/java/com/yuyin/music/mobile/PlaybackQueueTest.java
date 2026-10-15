package com.yuyin.music.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackQueueTest {
    @Test public void sequenceWrapsAndRemovedCurrentStartsAtEndOrBeginning() {
        assertEquals(-1, PlaybackQueue.next(0, 0, "sequence", 1, true, .5));
        assertEquals(0, PlaybackQueue.next(3, 2, "sequence", 1, true, .5));
        assertEquals(2, PlaybackQueue.next(3, 0, "sequence", -1, false, .5));
        assertEquals(0, PlaybackQueue.next(3, -1, "sequence", 1, false, .5));
        assertEquals(2, PlaybackQueue.next(3, -1, "sequence", -1, false, .5));
    }
    @Test public void repeatAppliesToAutomaticEndWhileMediaButtonsStillSkip() {
        assertEquals(1, PlaybackQueue.next(3, 1, "repeat", 1, true, .5));
        assertEquals(2, PlaybackQueue.next(3, 1, "repeat", 1, false, .5));
        assertEquals(0, PlaybackQueue.next(3, 1, "repeat", -1, false, .5));
        assertEquals(0, PlaybackQueue.next(1, 0, "shuffle", -1, false, .5));
    }
    @Test public void shuffleNeverRepeatsTheCurrentSongImmediately() {
        for (int i = 0; i < 5; i++) for (double sample : new double[] {0, .1, .5, .99, 1}) {
            int next = PlaybackQueue.next(5, i, "shuffle", 1, true, sample);
            assertTrue(next >= 0 && next < 5); assertNotEquals(i, next);
        }
    }
}
