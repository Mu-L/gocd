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
package com.thoughtworks.go.util;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;

import static com.thoughtworks.go.util.LogFixture.logFixtureFor;
import static org.assertj.core.api.Assertions.assertThat;

public class PerfTimerTest {

    @Test
    public void shouldRecordElapsedTime() {
        TestingClock clock = new TestingClock();

        PerfTimer timer = PerfTimer.start("Message", clock);
        clock.addSeconds(1);
        timer.stop();

        assertThat(timer.elapsed()).isEqualTo(1000L);
    }

    @Test
    public void shouldRecordElapsedTimeForDifferentTimes() {
        TestingClock clock = new TestingClock();

        PerfTimer timer = PerfTimer.start("Message", clock);

        clock.addSeconds(1);
        clock.addSeconds(1);
        timer.stop();

        assertThat(timer.elapsed()).isEqualTo(2000L);
    }

    @Test
    public void shouldLogTimeWithMessage() {
        TestingClock clock = new TestingClock();

        PerfTimer timer = PerfTimer.start("Message", clock);

        clock.addSeconds(1);

        try (LogFixture fixture = logFixtureFor(PerfTimer.class, Level.INFO)) {
            timer.stop();
            assertThat(fixture.getLog()).contains("Performance: Message took 1000ms");
        }
    }

    @Test
    public void shouldStopBeforeReportingElapsed() {
        TestingClock clock = new TestingClock();

        PerfTimer timer = PerfTimer.start("Message", clock);

        clock.addSeconds(1);

        assertThat(timer.elapsed()).isEqualTo(1000L);
    }

}
