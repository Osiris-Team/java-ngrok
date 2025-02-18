/*
 * Copyright (c) 2022 Alex Laird
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.github.alexdlaird.ngrok.process;

import com.github.alexdlaird.exception.NgrokException;
import com.github.alexdlaird.ngrok.NgrokTestCase;
import com.github.alexdlaird.ngrok.conf.JavaNgrokConfig;
import com.github.alexdlaird.ngrok.installer.NgrokInstaller;
import com.github.alexdlaird.util.MapUtils;
import com.github.alexdlaird.util.NgrokUtils;
import org.junit.jupiter.api.Test;
import org.jutils.jprocesses.JProcess;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.function.Function;

import static com.github.alexdlaird.ngrok.installer.NgrokInstaller.WINDOWS;
import static java.util.Objects.isNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class NgrokProcessTest extends NgrokTestCase {

    @Test
    public void testStart() {
        // GIVEN
        assertFalse(ngrokProcess.isRunning());

        // WHEN
        ngrokProcess.start();

        // THEN
        assertTrue(ngrokProcess.isRunning());
    }

    @Test
    public void testStop() {
        // GIVEN
        ngrokProcess.start();

        // WHEN
        ngrokProcess.stop();

        // THEN
        assertFalse(ngrokProcess.isRunning());
    }

    @Test
    public void testStartPortInUse() throws InterruptedException {
        // GIVEN
        assertFalse(ngrokProcess.isRunning());
        ngrokProcess.start();
        assertTrue(ngrokProcess.isRunning());
        final Path ngrokPath2 = Paths.get(javaNgrokConfig.getNgrokPath().getParent().toString(), "2", NgrokInstaller.getNgrokBin());
        final Path configPath2 = Paths.get(javaNgrokConfig.getConfigPath().getParent().toString(), "config2.yml");
        final JavaNgrokConfig javaNgrokConfig2 = new JavaNgrokConfig.Builder(javaNgrokConfig)
                .withNgrokPath(ngrokPath2)
                .withConfigPath(configPath2)
                .build();
        ngrokInstaller.installDefaultConfig(javaNgrokConfig2.getConfigPath(), MapUtils.of("web_addr", ngrokProcess.getApiUrl().substring(7)));

        // WHEN
        NgrokException exception = null;
        String error = null;
        for (int i = 0; isNull(error) && i < 10; ++i) {
            Thread.sleep(1000);

            ngrokProcess2 = new NgrokProcess(javaNgrokConfig2, ngrokInstaller);
            exception = assertThrows(NgrokException.class, ngrokProcess2::start);
            error = exception.getNgrokError();
        }

        // THEN
        assertNotNull(exception);
        assertNotNull(error);
        if (NgrokInstaller.getSystem().equals(WINDOWS)) {
            assertThat(exception.getMessage(), containsString("bind: Only one usage of each socket address"));
            assertThat(exception.getNgrokError(), containsString("bind: Only one usage of each socket address"));
        } else {
            assertThat(exception.getMessage(), containsString("bind: address already in use"));
            assertThat(exception.getNgrokError(), containsString("bind: address already in use"));
        }
        assertThat(exception.getNgrokLogs().size(), greaterThan(0));
        assertFalse(ngrokProcess2.isRunning());
    }

    @Test
    public void testExternalKill() throws InterruptedException, IOException, ParseException {
        // GIVEN
        ngrokProcess.start();
        assertTrue(ngrokProcess.isRunning());

        // WHEN
        JProcess processHandle = NgrokUtils.getRunningNgrokProcess(System.currentTimeMillis());

        // THEN
        assertNotNull(processHandle);
        processHandle.kill();
        long timeoutTime = System.currentTimeMillis() + 10 * 1000;
        while (processHandle.getExtraInfo().isAlive && ngrokProcess.isRunning() && System.currentTimeMillis() < timeoutTime) {
            Thread.sleep(50);
        }

        assertFalse(ngrokProcess.isRunning());

        // Check if there is a processHandle for ngrok now
        processHandle = NgrokUtils.getRunningNgrokProcess(System.currentTimeMillis());
        assertNull(processHandle);

        // THEN test we can successfully restart the process
        ngrokProcess.start();
        assertTrue(ngrokProcess.isRunning());
    }

    @Test
    public void testMultipleProcessesDifferentBinaries() {
        // GIVEN
        ngrokInstaller.installDefaultConfig(javaNgrokConfig.getConfigPath(), MapUtils.of("web_addr", "localhost:4040"));
        final Path ngrokPath2 = Paths.get(javaNgrokConfig.getNgrokPath().getParent().toString(), "2", NgrokInstaller.getNgrokBin());
        final Path configPath2 = Paths.get(javaNgrokConfig.getConfigPath().getParent().toString(), "config2.yml");
        final JavaNgrokConfig javaNgrokConfig2 = new JavaNgrokConfig.Builder(javaNgrokConfig)
                .withNgrokPath(ngrokPath2)
                .withConfigPath(configPath2)
                .build();
        ngrokInstaller.installDefaultConfig(javaNgrokConfig2.getConfigPath(), MapUtils.of("web_addr", "localhost:4041"));
        ngrokProcess2 = new NgrokProcess(javaNgrokConfig2, ngrokInstaller);

        // WHEN
        ngrokProcess.start();
        ngrokProcess2.start();

        // THEN
        assertTrue(ngrokProcess.isRunning());
        assertEquals("http://localhost:4040", ngrokProcess.getApiUrl());
        assertTrue(ngrokProcess2.isRunning());
        assertEquals("http://localhost:4041", ngrokProcess2.getApiUrl());
    }

    @Test
    public void testProcessLogs() {
        // WHEN
        ngrokProcess.start();

        // THEN
        for (final NgrokLog log : ngrokProcess.getProcessMonitor().getLogs()) {
            assertNotNull(log.getT());
            assertNotNull(log.getLvl());
            assertNotNull(log.getMsg());
        }
    }

    @Test
    public void testLogEventCallbackAndMaxLogs() throws InterruptedException {
        // GIVEN
        final Function<NgrokLog, Void> logEventCallbackMock = mock(Function.class);
        final JavaNgrokConfig javaNgrokConfig2 = new JavaNgrokConfig.Builder(javaNgrokConfig)
                .withLogEventCallback(logEventCallbackMock)
                .withMaxLogs(5)
                .build();
        ngrokProcess2 = new NgrokProcess(javaNgrokConfig2, ngrokInstaller);

        // WHEN
        ngrokProcess2.start();
        Thread.sleep(1000);

        // THEN
        assertThat(Mockito.mockingDetails(logEventCallbackMock).getInvocations().size(), greaterThan(ngrokProcess2.getProcessMonitor().getLogs().size()));
        assertEquals(5, ngrokProcess2.getProcessMonitor().getLogs().size());
    }

    @Test
    public void testNoMonitorThread() {
        // GIVEN
        final JavaNgrokConfig javaNgrokConfig2 = new JavaNgrokConfig.Builder(javaNgrokConfig)
                .withoutMonitoring()
                .build();
        ngrokProcess2 = new NgrokProcess(javaNgrokConfig2, ngrokInstaller);

        // WHEN
        ngrokProcess2.start();

        // THEN
        assertTrue(ngrokProcess2.isRunning());
        assertFalse(ngrokProcess2.getProcessMonitor().isMonitoring());
    }

    @Test
    public void testStartNoBinary() throws IOException, InterruptedException {
        // Due to Windows file locking behavior, wait a beat
        if (NgrokInstaller.getSystem().equals(WINDOWS)) {
            Thread.sleep(1000);
        }

        // GIVEN
        if (Files.exists(javaNgrokConfig.getNgrokPath())) {
            Files.delete(javaNgrokConfig.getNgrokPath());
        }

        // WHEN
        final NgrokException exception = assertThrows(NgrokException.class, ngrokProcess::start);

        // THEN
        assertThat(exception.getMessage(), containsString("ngrok binary was not found"));
        assertFalse(ngrokProcess.isRunning());
    }
}
