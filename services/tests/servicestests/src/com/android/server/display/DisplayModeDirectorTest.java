/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.DisplayModeDirector.DesiredDisplayModeSpecs;
import com.android.server.display.DisplayModeDirector.RefreshRateRange;
import com.android.server.display.DisplayModeDirector.Vote;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DisplayModeDirectorTest {
    // The tolerance within which we consider something approximately equals.
    private static final float FLOAT_TOLERANCE = 0.01f;

    private Context mContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private DisplayModeDirector createDisplayModeDirectorWithDisplayFpsRange(
            int minFps, int maxFps) {
        DisplayModeDirector director =
                new DisplayModeDirector(mContext, new Handler(Looper.getMainLooper()));
        int displayId = 0;
        int numModes = maxFps - minFps + 1;
        Display.Mode[] modes = new Display.Mode[numModes];
        for (int i = minFps; i <= maxFps; i++) {
            modes[i - minFps] = new Display.Mode(
                    /*modeId=*/i, /*width=*/1000, /*height=*/1000, /*refreshRate=*/i);
        }
        SparseArray<Display.Mode[]> supportedModesByDisplay = new SparseArray<>();
        supportedModesByDisplay.put(displayId, modes);
        director.injectSupportedModesByDisplay(supportedModesByDisplay);
        SparseArray<Display.Mode> defaultModesByDisplay = new SparseArray<>();
        defaultModesByDisplay.put(displayId, modes[0]);
        director.injectDefaultModeByDisplay(defaultModesByDisplay);
        return director;
    }

    @Test
    public void testDisplayModeVoting() {
        int displayId = 0;

        // With no votes present, DisplayModeDirector should allow any refresh rate.
        assertEquals(new DesiredDisplayModeSpecs(/*baseModeId=*/60,
                             new RefreshRateRange(0f, Float.POSITIVE_INFINITY)),
                createDisplayModeDirectorWithDisplayFpsRange(60, 90).getDesiredDisplayModeSpecs(
                        displayId));

        int numPriorities =
                DisplayModeDirector.Vote.MAX_PRIORITY - DisplayModeDirector.Vote.MIN_PRIORITY + 1;

        // Ensure vote priority works as expected. As we add new votes with higher priority, they
        // should take precedence over lower priority votes.
        {
            int minFps = 60;
            int maxFps = 90;
            DisplayModeDirector director = createDisplayModeDirectorWithDisplayFpsRange(60, 90);
            assertTrue(2 * numPriorities < maxFps - minFps + 1);
            SparseArray<Vote> votes = new SparseArray<>();
            SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
            votesByDisplay.put(displayId, votes);
            for (int i = 0; i < numPriorities; i++) {
                int priority = Vote.MIN_PRIORITY + i;
                votes.put(priority, Vote.forRefreshRates(minFps + i, maxFps - i));
                director.injectVotesByDisplay(votesByDisplay);
                assertEquals(new DesiredDisplayModeSpecs(
                                /*baseModeId=*/minFps + i,
                                new RefreshRateRange(minFps + i, maxFps - i)),
                        director.getDesiredDisplayModeSpecs(displayId));
            }
        }

        // Ensure lower priority votes are able to influence the final decision, even in the
        // presence of higher priority votes.
        {
            assertTrue(numPriorities >= 2);
            DisplayModeDirector director = createDisplayModeDirectorWithDisplayFpsRange(60, 90);
            SparseArray<Vote> votes = new SparseArray<>();
            SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
            votesByDisplay.put(displayId, votes);
            votes.put(Vote.MAX_PRIORITY, Vote.forRefreshRates(65, 85));
            votes.put(Vote.MIN_PRIORITY, Vote.forRefreshRates(70, 80));
            director.injectVotesByDisplay(votesByDisplay);
            assertEquals(new DesiredDisplayModeSpecs(/*baseModeId=*/70,
                                 new RefreshRateRange(70, 80)),
                    director.getDesiredDisplayModeSpecs(displayId));
        }
    }

    @Test
    public void testVotingWithFloatingPointErrors() {
        int displayId = 0;
        DisplayModeDirector director = createDisplayModeDirectorWithDisplayFpsRange(50, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(displayId, votes);
        float error = FLOAT_TOLERANCE / 4;
        votes.put(Vote.PRIORITY_USER_SETTING_PEAK_REFRESH_RATE, Vote.forRefreshRates(0, 60));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forRefreshRates(60 + error, 60 + error));
        votes.put(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE,
                Vote.forRefreshRates(60 - error, 60 - error));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);

        Truth.assertThat(desiredSpecs.refreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        Truth.assertThat(desiredSpecs.refreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        Truth.assertThat(desiredSpecs.baseModeId).isEqualTo(60);
    }
}
