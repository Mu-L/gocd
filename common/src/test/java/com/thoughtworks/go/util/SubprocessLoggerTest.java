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
import com.thoughtworks.go.process.CurrentProcess;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.thoughtworks.go.util.LogFixture.logFixtureFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SubprocessLoggerTest {
    private SubprocessLogger logger;

    @AfterEach
    public void tearDown() {
        Runtime.getRuntime().removeShutdownHook(logger.exitHook());
    }

    @Test
    public void shouldNotLogAnythingWhenNoChildProcessesFound() {
        CurrentProcess currentProcess = mock(CurrentProcess.class);
        logger = new SubprocessLogger(currentProcess);
        try (LogFixture log = logFixtureFor(SubprocessLogger.class, Level.TRACE)) {
            logger.run();
            String result;
            synchronized (log) {
                result = log.getLog();
            }
            assertThat(result).isEqualTo("");
        }
    }

    @Test
    public void shouldLogDefaultMessageWhenNoMessageGiven() {
        logger = new SubprocessLogger(stubProcess());
        String allLogs;
        try (LogFixture log = logFixtureFor(SubprocessLogger.class, Level.TRACE)) {
            logger.run();
            String result;
            synchronized (log) {
                result = log.getLog();
            }
            allLogs = result;
        }
        assertThat(allLogs).contains("Logged all subprocesses.");
    }

    @Test
    public void shouldLogAllTheRunningChildProcesses() {
        logger = new SubprocessLogger(stubProcess());
        String allLogs;
        try (LogFixture log = logFixtureFor(SubprocessLogger.class, Level.TRACE)) {
            logger.registerAsExitHook("foo bar baz");
            logger.run();
            String result;
            synchronized (log) {
                result = log.getLog();
            }
            allLogs = result;
        }
        Assertions.assertThat(allLogs).isEqualToNormalizingNewlines("""
                WARN foo bar baz
                101
                103

                """);
        assertThat(allLogs).doesNotContain("102");
    }

    private CurrentProcess stubProcess() {
        CurrentProcess currentProcess = mock(CurrentProcess.class);
        List<ProcessHandle> subProcesses = List.of(
                stubProcessHandle(101, 100, "command-1", "owner-1"),
                stubProcessHandle(103, 100, "command-1a", "owner-1")
        );
        when(currentProcess.immediateChildren()).thenReturn(subProcesses);
        return currentProcess;
    }

    private ProcessHandle stubProcessHandle(long pid, long parentPid, String command, String owner) {
        ProcessHandle parentProcess = mock(ProcessHandle.class);
        when(parentProcess.pid()).thenReturn(parentPid);

        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(info.command()).thenReturn(Optional.of(command));
        when(info.user()).thenReturn(Optional.of(owner));

        ProcessHandle processHandle = mock(ProcessHandle.class);
        when(processHandle.pid()).thenReturn(pid);
        when(processHandle.parent()).thenReturn(Optional.of(parentProcess));
        when(processHandle.info()).thenReturn(info);
        when(processHandle.toString()).thenReturn(String.format("%d", pid));

        return processHandle;
    }

    @Test
    public void shouldRegisterItselfAsExitHook() {
        logger = new SubprocessLogger(new CurrentProcess());
        logger.registerAsExitHook("foo");
        try {
            Runtime.getRuntime().addShutdownHook(logger.exitHook());
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).isEqualTo("Hook previously registered");
        } finally {
            Runtime.getRuntime().removeShutdownHook(logger.exitHook());
        }
    }
}
